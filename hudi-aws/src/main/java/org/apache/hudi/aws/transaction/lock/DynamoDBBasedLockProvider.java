/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.aws.transaction.lock;

import org.apache.hudi.aws.credentials.HoodieAWSCredentialsProviderFactory;
import org.apache.hudi.common.config.LockConfiguration;
import org.apache.hudi.common.lock.LockProvider;
import org.apache.hudi.common.lock.LockState;
import org.apache.hudi.common.util.StringUtils;
import org.apache.hudi.config.DynamoDbBasedLockConfig;
import org.apache.hudi.exception.HoodieLockException;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.dynamodbv2.AcquireLockOptions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBLockClient;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBLockClientOptions;
import com.amazonaws.services.dynamodbv2.LockItem;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.BillingMode;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.LockNotGrantedException;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.NotThreadSafe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A DynamoDB based lock. This {@link LockProvider} implementation allows to lock table operations
 * using DynamoDB. Users need to have access to AWS DynamoDB to be able to use this lock.
 */
@NotThreadSafe
public class DynamoDBBasedLockProvider implements LockProvider<LockItem> {

  private static final Logger LOG = LoggerFactory.getLogger(DynamoDBBasedLockProvider.class);

  private static final String DYNAMODB_ATTRIBUTE_NAME = "key";

  private final AmazonDynamoDBLockClient client;
  private final String tableName;
  private final String dynamoDBPartitionKey;
  protected final DynamoDbBasedLockConfig dynamoDBLockConfiguration;
  private volatile LockItem lock;

  public DynamoDBBasedLockProvider(final LockConfiguration lockConfiguration, final Configuration conf) {
    this(lockConfiguration, conf, null);
  }

  public DynamoDBBasedLockProvider(final LockConfiguration lockConfiguration, final Configuration conf, AmazonDynamoDB dynamoDB) {
    this.dynamoDBLockConfiguration = DynamoDbBasedLockConfig.newBuilder()
        .fromProperties(lockConfiguration.getConfig())
        .build();
    this.tableName = dynamoDBLockConfiguration.getString(DynamoDbBasedLockConfig.DYNAMODB_LOCK_TABLE_NAME);
    this.dynamoDBPartitionKey = dynamoDBLockConfiguration.getString(DynamoDbBasedLockConfig.DYNAMODB_LOCK_PARTITION_KEY);
    long leaseDuration = dynamoDBLockConfiguration.getInt(DynamoDbBasedLockConfig.LOCK_ACQUIRE_WAIT_TIMEOUT_MS_PROP_KEY);
    if (dynamoDB == null) {
      dynamoDB = getDynamoDBClient();
    }
    // build the dynamoDb lock client
    this.client = new AmazonDynamoDBLockClient(
        AmazonDynamoDBLockClientOptions.builder(dynamoDB, tableName)
            .withTimeUnit(TimeUnit.MILLISECONDS)
            .withLeaseDuration(leaseDuration)
            .withHeartbeatPeriod(leaseDuration / 3)
            .withCreateHeartbeatBackgroundThread(true)
            .build());

    if (!this.client.lockTableExists()) {
      createLockTableInDynamoDB(dynamoDB, tableName);
    }
  }

  @Override
  public boolean tryLock(long time, TimeUnit unit) {
    LOG.info(generateLogStatement(LockState.ACQUIRING, generateLogSuffixString()));
    try {
      lock = client.acquireLock(AcquireLockOptions.builder(dynamoDBPartitionKey)
              .withAdditionalTimeToWaitForLock(time)
              .withTimeUnit(TimeUnit.MILLISECONDS)
              .build());
      LOG.info(generateLogStatement(LockState.ACQUIRED, generateLogSuffixString()));
    } catch (InterruptedException e) {
      throw new HoodieLockException(generateLogStatement(LockState.FAILED_TO_ACQUIRE, generateLogSuffixString()), e);
    } catch (LockNotGrantedException e) {
      return false;
    }
    return lock != null && !lock.isExpired();
  }

  @Override
  public void unlock() {
    try {
      LOG.info(generateLogStatement(LockState.RELEASING, generateLogSuffixString()));
      if (lock == null) {
        return;
      }
      if (!client.releaseLock(lock)) {
        LOG.warn("The lock has already been stolen");
      }
      lock = null;
      LOG.info(generateLogStatement(LockState.RELEASED, generateLogSuffixString()));
    } catch (Exception e) {
      throw new HoodieLockException(generateLogStatement(LockState.FAILED_TO_RELEASE, generateLogSuffixString()), e);
    }
  }

  @Override
  public void close() {
    try {
      if (lock != null) {
        if (!client.releaseLock(lock)) {
          LOG.warn("The lock has already been stolen");
        }
        lock = null;
      }
      this.client.close();
    } catch (Exception e) {
      LOG.error(generateLogStatement(LockState.FAILED_TO_RELEASE, generateLogSuffixString()));
    }
  }

  @Override
  public LockItem getLock() {
    return lock;
  }

  private AmazonDynamoDB getDynamoDBClient() {
    String region = this.dynamoDBLockConfiguration.getString(DynamoDbBasedLockConfig.DYNAMODB_LOCK_REGION);
    String endpointURL = this.dynamoDBLockConfiguration.contains(DynamoDbBasedLockConfig.DYNAMODB_ENDPOINT_URL.key())
                          ? this.dynamoDBLockConfiguration.getString(DynamoDbBasedLockConfig.DYNAMODB_ENDPOINT_URL)
                          : RegionUtils.getRegion(region).getServiceEndpoint(AmazonDynamoDB.ENDPOINT_PREFIX);
    AwsClientBuilder.EndpointConfiguration dynamodbEndpoint =
            new AwsClientBuilder.EndpointConfiguration(endpointURL, region);
    return AmazonDynamoDBClientBuilder.standard()
            .withEndpointConfiguration(dynamodbEndpoint)
            .withCredentials(HoodieAWSCredentialsProviderFactory.getAwsCredentialsProvider(dynamoDBLockConfiguration.getProps()))
            .build();
  }

  private void createLockTableInDynamoDB(AmazonDynamoDB dynamoDB, String tableName) {
    String billingMode = dynamoDBLockConfiguration.getString(DynamoDbBasedLockConfig.DYNAMODB_LOCK_BILLING_MODE);
    KeySchemaElement partitionKeyElement = new KeySchemaElement();
    partitionKeyElement.setAttributeName(DYNAMODB_ATTRIBUTE_NAME);
    partitionKeyElement.setKeyType(KeyType.HASH);

    List<KeySchemaElement> keySchema = new ArrayList<>();
    keySchema.add(partitionKeyElement);

    Collection<AttributeDefinition> attributeDefinitions = new ArrayList<>();
    attributeDefinitions.add(new AttributeDefinition().withAttributeName(DYNAMODB_ATTRIBUTE_NAME).withAttributeType(ScalarAttributeType.S));

    CreateTableRequest createTableRequest = new CreateTableRequest(tableName, keySchema);
    createTableRequest.setAttributeDefinitions(attributeDefinitions);
    createTableRequest.setBillingMode(billingMode);
    if (billingMode.equals(BillingMode.PROVISIONED.name())) {
      createTableRequest.setProvisionedThroughput(new ProvisionedThroughput()
          .withReadCapacityUnits(dynamoDBLockConfiguration.getLong(DynamoDbBasedLockConfig.DYNAMODB_LOCK_READ_CAPACITY))
          .withWriteCapacityUnits(dynamoDBLockConfiguration.getLong(DynamoDbBasedLockConfig.DYNAMODB_LOCK_WRITE_CAPACITY)));
    }
    dynamoDB.createTable(createTableRequest);

    LOG.info("Creating dynamoDB table " + tableName + ", waiting for table to be active");
    try {
      TableUtils.waitUntilActive(dynamoDB, tableName, dynamoDBLockConfiguration.getInt(DynamoDbBasedLockConfig.DYNAMODB_LOCK_TABLE_CREATION_TIMEOUT), 20 * 1000);
    } catch (TableUtils.TableNeverTransitionedToStateException e) {
      throw new HoodieLockException("Created dynamoDB table never transits to active", e);
    } catch (InterruptedException e) {
      throw new HoodieLockException("Thread interrupted while waiting for dynamoDB table to turn active", e);
    }
    LOG.info("Created dynamoDB table " + tableName);
  }

  private String generateLogSuffixString() {
    return StringUtils.join("DynamoDb table = ", tableName, ", partition key = ", dynamoDBPartitionKey);
  }

  protected String generateLogStatement(LockState state, String suffix) {
    return StringUtils.join(state.name(), " lock at ", suffix);
  }
}
