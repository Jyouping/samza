---
layout: page
title: Run as embedded library.
---
<!--
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

- [Introduction](#introduction)
- [User guide](#user-guide)
     - [Setup dependencies](#setup-dependencies)
     - [Configuration](#configuration)
     - [Code sample](#code-sample)
- [Quick start guide](#quick-start-guide)
  - [Setup zookeeper](#setup-zookeeper)
  - [Setup kafka](#setup-kafka)
  - [Build binaries](#build-binaries)
  - [Deploy binaries](#deploy-binaries)
  - [Inspect results](#inspect-results)
- [Coordinator internals](#coordinator-internals)

#

# Introduction

With Samza 0.13.0, the deployment model of samza jobs has been simplified and decoupled from YARN. _Standalone_ model provides the stream processing capabilities of samza packaged in the form of a library with pluggable coordination. This library model offers an easier integration path  and promotes a flexible deployment model for an application. Using the standalone mode, you can leverage Samza processors directly in your application and deploy Samza applications to self-managed clusters.

A standalone application typically is comprised of multiple _stream processors_. A _stream processor_ encapsulates a user defined processing function and is responsible for processing a subset of input topic partitions. A stream processor of a standalone application is uniquely identified by a _processorId_.

Samza provides pluggable job _coordinator_ layer to perform leader election and assign work to the stream processors. Standalone supports Zookeeper coordination out of the box and uses it for distributed coordination between the stream processors of standalone application. A processor can become part of a standalone application by setting its app.name(Ex: app.name=group\_1) and joining the group.

In samza standalone, the input topic partitions are distributed between the available processors dynamically at runtime. In each standalone application, one stream processor will be chosen as a leader initially to mediate the assignment of input topic partitions to the stream processors. If the number of available processors changes(for example, if a processors is shutdown or added), then the leader processor will regenerate the partition assignments and re-distribute it to all the processors.

On processor group change, the act of re-assigning input topic partitions to the remaining live processors in the group is known as rebalancing the group. On failure of the leader processor of a standalone application, an another stream processor of the standalone application will be chosen as leader.

## User guide

Samza standalone is designed to help you to have more control over the deployment of the application. So it is your responsibility to configure and deploy the processors. In case of ZooKeeper coordination, you have to configure the URL for an instance of ZooKeeper.

A stream processor is identified by a unique processorID which is generated by the pluggable ProcessorIdGenerator abstraction. ProcessorId of the stream processor is used with the coordination service. Samza supports UUID based ProcessorIdGenerator out of the box.

The diagram below shows a input topic with three partitions and an standalone application with three processors consuming messages from it.

<img src="/img/versioned/learn/documentation/standalone/standalone-application.jpg" alt="Standalone application" height="550px" width="700px" align="middle">

When a group is first initialized, each stream processor typically starts processing messages from either the earliest or latest offset of the input topic partition. The messages in each partition are sequentially delivered to the user defined processing function. As the stream processor makes progress, it commits the offsets of the messages it has successfully processed. For example, in the figure above, the stream processor position is at offset 7 and its last committed offset is at offset 3.

When a input partition is reassigned to another processor in the group, the initial position is set to the last committed offset. If the processor-1 in the example above suddenly crashed, then the live processor taking over the partition would begin consumption from offset 3. In that case, it would not have to reprocess the messages up to the crashed processor's position of 3.

### Setup dependencies

Add the following samza-standalone maven dependencies to your project.

```xml
<dependency>
    <groupId>org.apache.samza</groupId>
    <artifactId>samza-kafka_2.11</artifactId>
    <version>1.0</version>
</dependency>
<dependency>
    <groupId>org.apache.samza</groupId>
    <artifactId>samza-core_2.11</artifactId>
    <version>1.0</version>
</dependency>
<dependency>
    <groupId>org.apache.samza</groupId>
    <artifactId>samza-api</artifactId>
    <version>1.0</version>
</dependency>
```

### Configuration

A samza standalone application requires you to define the following mandatory configurations:

```bash
job.coordinator.factory=org.apache.samza.zk.ZkJobCoordinatorFactory
job.coordinator.zk.connect=your_zk_connection(for local zookeeper, use localhost:2181)
task.name.grouper.factory=org.apache.samza.container.grouper.task.GroupByContainerIdsFactory 
```

You have to configure the stream processor with the kafka brokers as defined in the following sample(we have assumed that the broker is running on localhost):

```bash 
systems.kafka.samza.factory=org.apache.samza.system.kafka.KafkaSystemFactory
systems.kafka.samza.msg.serde=json
systems.kafka.consumer.zookeeper.connect=localhost:2181
systems.kafka.producer.bootstrap.servers=localhost:9092 
```

### Code sample

Here&#39;s a sample standalone application with app.name set to sample-test. Running this class would launch a stream processor.

```java
public class PageViewEventExample implements StreamApplication {

  public static void main(String[] args) {
    CommandLine cmdLine = new CommandLine();
    OptionSet options = cmdLine.parser().parse(args);
    Config config = cmdLine.loadConfig(options);

    ApplicationRunner runner = ApplicationRunners.getApplicationRunner(ApplicationClassUtils.fromConfig(config), config);
    runner.run();
    runner.waitForFinish();
  }

  @Override
  public void describe(StreamAppDescriptor appDesc) {
     MessageStream<PageViewEvent> pageViewEvents = null;
     pageViewEvents = appDesc.getInputStream("inputStream", new JsonSerdeV2<>(PageViewEvent.class));
     OutputStream<KV<String, PageViewCount>> pageViewEventPerMemberStream =
         appDesc.getOutputStream("outputStream",  new JsonSerdeV2<>(PageViewEvent.class));
     pageViewEvents.sendTo(pageViewEventPerMemberStream);
  }
}
```

## Quick start guide

The [Hello-samza](https://github.com/apache/samza-hello-samza/) project contains sample Samza standalone applications. Here are step by step instruction guide to install, build and run a standalone application binaries using the local zookeeper cluster for coordination. Check out the hello-samza project by running the following commands:

```bash
git clone https://git.apache.org/samza-hello-samza.git hello-samza
cd hello-samza 
```

### Setup Zookeeper

Run the following command to install and start a local zookeeper cluster.

```bash
./bin/grid install zookeeper
./bin/grid start zookeeper
```

### Setup Kafka

Run the following command to install and start a local kafka cluster.

```bash
./bin/grid install kafka
./bin/grid start zookeeper
```

### Build binaries

Before you can run the standalone job, you need to build a package for it using the following command.

```bash
mvn clean package
mkdir -p deploy/samza
tar -xvf ./target/hello-samza-0.15.0-SNAPSHOT-dist.tar.gz -C deploy/samza 
```

### Deploy binaries

To run the sample standalone application [WikipediaZkLocalApplication](https://github.com/apache/samza-hello-samza/blob/master/src/main/java/samza/examples/wikipedia/application/WikipediaZkLocalApplication.java)

```bash
./bin/deploy.sh
./deploy/samza/bin/run-class.sh samza.examples.wikipedia.application.WikipediaZkLocalApplication  --config-factory=org.apache.samza.config.factories.PropertiesConfigFactory --config-path=file://$PWD/deploy/samza/config/wikipedia-application-local-runner.properties
```

### Inspect results

The standalone application reads messages from the wikipedia-edits topic, and calculates counts, every ten seconds, for all edits that were made during that window. It outputs these counts to the local wikipedia-stats kafka topic. To inspect events in output topic, run the following command.

```bash
./deploy/kafka/bin/kafka-console-consumer.sh  --zookeeper localhost:2181 --topic wikipedia-stats
```

Events produced to the output topic from the standalone application launched above will be of the following form:

```
{"is-talk":2,"bytes-added":5276,"edits":13,"unique-titles":13}
{"is-bot-edit":1,"is-talk":3,"bytes-added":4211,"edits":30,"unique-titles":30,"is-unpatrolled":1,"is-new":2,"is-minor":7}
```

# Coordinator internals

A samza application is comprised of multiple stream processors. A processor can become part of a standalone application by setting its app.name(Ex: app.name=group\_1) and joining the group. In samza standalone, the input topic partitions are distributed between the available processors dynamically at runtime. If the number of available processors changes(for example, if some processors are shutdown or added), then the partition assignments will be regenerated and re-distributed to all the processors. One processor will be elected as leader and it will generate the partition assignments and distribute it to the other processors in the group.

To mediate the partition assignments between processors, samza standalone relies upon a coordination service. The main responsibilities of coordination service are the following:

**Leader Election** - Elects a single processor to generate the partition assignments and distribute it to other processors in the group.

**Distributed barrier** - Coordination primitive used by the processors to reach consensus(agree) on an partition assignment.

By default, embedded samza uses Zookeeper for coordinating between processors of an application and store the partition assignment state. Coordination sequence for a standalone application is listed below:

1. Each processor(participant) will register with the coordination service(e.g: Zookeeper) with its participant ID.

2. One of the participants will be elected as the leader.

3. The leader will monitor the list of all the active participants.

4. Whenever the list of the participants changes in a group, the leader will generate a new partition assignments for the current participants and persist it to a common storage.

5. Participants are notified that the new partition assignment is available. Notification is done through the coordination service(e.g. ZooKeeper).

6. The participants will stop processing, pick up the new partition assignment, and then resume processing.

In order to ensure that no two partitions are processed by different processors, processing is paused and all the processors will synchronize on a distributed barrier. Once all the processors are paused, the new partition assignments are applied, after which processing resumes.