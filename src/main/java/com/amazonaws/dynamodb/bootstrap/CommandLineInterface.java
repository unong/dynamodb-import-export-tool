/*
 * Copyright 2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.dynamodb.bootstrap;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.dynamodb.bootstrap.constants.BootstrapConstants;
import com.amazonaws.dynamodb.bootstrap.exception.NullReadCapacityException;
import com.amazonaws.dynamodb.bootstrap.exception.SectionOutOfRangeException;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndex;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndexDescription;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The interface that parses the arguments, and begins to transfer data from one
 * DynamoDB table to another
 */
public class CommandLineInterface {

    /**
     * Logger for the DynamoDBBootstrapWorker.
     */
    private static final Logger LOGGER = LogManager
            .getLogger(CommandLineInterface.class);

    /**
     * Main class to begin transferring data from one DynamoDB table to another
     * DynamoDB table.
     * 
     * @param args
     */
    public static void main(String[] args) {
        CommandLineArgs params = new CommandLineArgs();
        JCommander cmd = new JCommander(params);

        try {
            // parse given arguments
            cmd.parse(args);
        } catch (ParameterException e) {
            LOGGER.error(e);
            JCommander.getConsole().println(e.getMessage());
            cmd.usage();
            System.exit(1);
        }

        // show usage information if help flag exists
        if (params.getHelp()) {
            cmd.usage();
            return;
        }
        final String sourceEndpoint = params.getSourceEndpoint();
        final String destinationEndpoint = params.getDestinationEndpoint();
        final String destinationTable = params.getDestinationTable();
        final String sourceTable = params.getSourceTable();
        final double readThroughputRatio = params.getReadThroughputRatio();
        final double writeThroughputRatio = params.getWriteThroughputRatio();
        final int maxWriteThreads = params.getMaxWriteThreads();
        final boolean consistentScan = params.getConsistentScan();

        final ClientConfiguration sourceConfig = new ClientConfiguration().withMaxConnections(BootstrapConstants.MAX_CONN_SIZE);
        final ClientConfiguration destinationConfig = new ClientConfiguration().withMaxConnections(BootstrapConstants.MAX_CONN_SIZE);

        final AmazonDynamoDBClient sourceClient = new AmazonDynamoDBClient(
                new DefaultAWSCredentialsProviderChain(), sourceConfig);
        final AmazonDynamoDBClient destinationClient = new AmazonDynamoDBClient(
                new DefaultAWSCredentialsProviderChain(), destinationConfig);
        sourceClient.setEndpoint(sourceEndpoint);
        destinationClient.setEndpoint(destinationEndpoint);

        TableDescription readTableDescription = sourceClient.describeTable(
                sourceTable).getTable();

        try {
            TableDescription writeTableDescription = destinationClient
                    .describeTable(destinationTable).getTable();
        } catch (Exception e) {
            LOGGER.warn("cannot find table on destination dynamo, so this task will make table same as source one", e);
            Collection<GlobalSecondaryIndex> gsis = new ArrayList<>();
            if(readTableDescription.getGlobalSecondaryIndexes() != null) {
                for(GlobalSecondaryIndexDescription gsid : readTableDescription.getGlobalSecondaryIndexes()) {
                    GlobalSecondaryIndex gsi = new GlobalSecondaryIndex()
                            .withKeySchema(gsid.getKeySchema())
                            .withIndexName(gsid.getIndexName())
                            .withProjection(gsid.getProjection())
                            .withProvisionedThroughput(new ProvisionedThroughput(gsid.getProvisionedThroughput().getReadCapacityUnits(), gsid.getProvisionedThroughput().getWriteCapacityUnits()))
                            ;
                    gsis.add(gsi);
                }
            }
            CreateTableRequest request = new CreateTableRequest(
                    readTableDescription.getTableName(),
                    readTableDescription.getKeySchema()
            )
                    .withAttributeDefinitions(readTableDescription.getAttributeDefinitions())
                    .withProvisionedThroughput(new ProvisionedThroughput(readTableDescription.getProvisionedThroughput().getReadCapacityUnits(), readTableDescription.getProvisionedThroughput().getWriteCapacityUnits()))
                    ;
            if(!gsis.isEmpty()) {
                request.setGlobalSecondaryIndexes(gsis);
            }
            destinationClient.createTable(request);
        }
        TableDescription writeTableDescription = destinationClient
                .describeTable(destinationTable).getTable();

        int numSegments = 10;
        try {
            numSegments = DynamoDBBootstrapWorker
                    .getNumberOfSegments(readTableDescription);
        } catch (NullReadCapacityException e) {
            LOGGER.warn("Number of segments not specified - defaulting to "
                    + numSegments, e);
        }

        final double readThroughput = calculateThroughput(readTableDescription,
                readThroughputRatio, true);
        final double writeThroughput = calculateThroughput(
                writeTableDescription, writeThroughputRatio, false);

        try {
            ExecutorService sourceExec = getSourceThreadPool(numSegments);
            ExecutorService destinationExec = getDestinationThreadPool(maxWriteThreads);
            DynamoDBConsumer consumer = new DynamoDBConsumer(destinationClient,
                    destinationTable, writeThroughput, destinationExec);

            final DynamoDBBootstrapWorker worker = new DynamoDBBootstrapWorker(
                    sourceClient, readThroughput, sourceTable, sourceExec,
                    params.getSection(), params.getTotalSections(), numSegments, consistentScan);

            LOGGER.info("Starting transfer...");
            worker.pipe(consumer);
            LOGGER.info("Finished Copying Table.");
        } catch (ExecutionException e) {
            LOGGER.error("Encountered exception when executing transfer.", e);
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted when executing transfer.", e);
            System.exit(1);
        } catch (SectionOutOfRangeException e) {
            LOGGER.error("Invalid section parameter", e);
        }
    }

    /**
     * returns the provisioned throughput based on the input ratio and the
     * specified DynamoDB table provisioned throughput.
     */
    private static double calculateThroughput(
            TableDescription tableDescription, double throughputRatio,
            boolean read) {
        if (read) {
            return tableDescription.getProvisionedThroughput()
                    .getReadCapacityUnits() * throughputRatio;
        }
        return tableDescription.getProvisionedThroughput()
                .getWriteCapacityUnits() * throughputRatio;
    }

    /**
     * Returns the thread pool for the destination DynamoDB table.
     */
    private static ExecutorService getDestinationThreadPool(int maxWriteThreads) {
        int corePoolSize = BootstrapConstants.DYNAMODB_CLIENT_EXECUTOR_CORE_POOL_SIZE;
        if (corePoolSize > maxWriteThreads) {
            corePoolSize = maxWriteThreads - 1;
        }
        final long keepAlive = BootstrapConstants.DYNAMODB_CLIENT_EXECUTOR_KEEP_ALIVE;
        ExecutorService exec = new ThreadPoolExecutor(corePoolSize,
                maxWriteThreads, keepAlive, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(maxWriteThreads),
                new ThreadPoolExecutor.CallerRunsPolicy());
        return exec;
    }

    /**
     * Returns the thread pool for the source DynamoDB table.
     */
    private static ExecutorService getSourceThreadPool(int numSegments) {
        int corePoolSize = BootstrapConstants.DYNAMODB_CLIENT_EXECUTOR_CORE_POOL_SIZE;
        if (corePoolSize > numSegments) {
            corePoolSize = numSegments - 1;
        }

        final long keepAlive = BootstrapConstants.DYNAMODB_CLIENT_EXECUTOR_KEEP_ALIVE;
        ExecutorService exec = new ThreadPoolExecutor(corePoolSize,
                numSegments, keepAlive, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(numSegments),
                new ThreadPoolExecutor.CallerRunsPolicy());
        return exec;
    }

}
