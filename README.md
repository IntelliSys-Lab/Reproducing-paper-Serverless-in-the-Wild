# Reproducing Serverless in the Wild

This project builds the Histogram Policy in [Serverless in the Wild: Characterizing and Optimizing the Serverless Workload at a Large Cloud Provider](https://www.microsoft.com/en-us/research/uploads/prod/2020/05/serverless-ATC20.pdf) in OpenWhisk.

### Introduction

As shown below, there are three steps to reproduce the paper. First, implement the hybrid policy in Load Balancer, where all invocations pass through. Second, as the Load Balancer sends all invocation requests called Activation Messages to Invokers, we should add the keep-alive and pre-warm parameters in the Activation Message API. Finally, modify the keep-alive value of the corresponding container.

<img src="/Users/suiyifan/Library/Application Support/typora-user-images/image-20230318172857027.png" alt="image-20230318172857027" style="zoom:50%;" />



### Modification

To modify the original OpenWhisk and implement the Histogram Policy, we need to change three components.

##### Controller

Since all invocations pass through the **Load Balancer**, it is the ideal place to manage histograms and other metadata required for the hybrid policy. We add new logic to the Load Balancer to implement the hybrid policy and to update the keep-alive and pre-warm parameters after each invocation. We also modify the Load Balancer to **publish the pre-warming messages**.

In the project, all the modification is in the [ShardingContainerPoolBalancer.scala](core/controller/src/main/scala/org/apache/openwhisk/core/loadBalancer/ShardingContainerPoolBalancer.scala) . Please compare this file with the original ShardingContainerPoolBalancer to see how to implement the Histogram Policy.

##### API

We send the latest keep-alive parameter for a function to the corresponding Invoker alongside the invocation request.
To do this, we add a field to the **ActivationMessage** API, specifying the keep-alive duration in minutes.

The modification is simple: just add two parameters in the [Message.scala](common/scala/src/main/scala/org/apache/openwhisk/core/connector/Message.scala): the pre-warm parameter and the keep-alive parameter.

##### Invoker

The Invoker unloads Docker containers that have timed-out in  [ContainerProxy.scala](core/invoker/src/main/scala/org/apache/openwhisk/core/containerpool/ContainerProxy.scala) . We modify this module to unload containers based on the keep-alive parameter received from **ActivationMessage**.

As for the pre-warm mechanism, we need to change [ContainerPool.scala](core/invoker/src/main/scala/org/apache/openwhisk/core/containerpool/ContainerPool.scala). When a container has finished the task, based on the Histogram Policy, it should be destroyed and send a **PreWarmMessage** that contains the pre-warm parameter to the ContainerPool. Then ContainerPool will pre-warm a new container after a few minutes.

##### An Extra Modification (Optional)

To improve resource utilization, I make an extra modification. The case object **NumofPre** in  [ContainerPool.scala](core/invoker/src/main/scala/org/apache/openwhisk/core/containerpool/ContainerPool.scala) is used to  control the number of pre-warm containers in the pool. I want to make sure there is always at most **one** free pre-warm container in the pool for each action. Here is the reason:

As shown below, if an unpredicted invocation arrives while ContainerA has not been pre-warmed, the Histogram Policy will treat it as a cold start, executing it immediately and pre-warm ContainerB after a few minutes. Eventually, there are two idle containers in the pool while only at most one of them will be used, causing huge resource wastage (a whole keep-alive period). So I add an extra mechanism of pre-warming. Once a container is about to be pre-warmed, the scheduler will check whether there has been an idle container in the pool. If an idle container exists, the new container will not be pre-warmed.

<img src="/Users/suiyifan/Desktop/openwhisk-master-copy1226的副本/problem.png" alt="problem" style="zoom:30%;" />

You can just delete this object. It won't affect the actual running of OpenWhisk.



### Experiment

After the full reproduction, you can compare the Histogram Policy with OpenWhisk's original fixed-10 min policy.

##### Step1. Delete the default pre-warm mechanism

OpenWhisk originally calls their prewarmed containers “stemcell”, you just need to make sure to wipe out all stemcell container configurations in https://github.com/apache/openwhisk/blob/master/ansible/files/runtimes.json#L30. 

##### Step2. Design an experiment

You can use [Azure's Function Trace](https://github.com/Azure/AzurePublicDataset#azure-functions-traces) for the best simulation.

For just a simple comparison, it's OK to just invoke a single action. This is part of my test script :

```sh
#!/bin/bash
echo "Start Test"
wsk -i action update pyactionL2 pyaction2.py 
wsk -i action invoke pyactionL2 --result --param topic "1"

sleep 180
wsk -i action invoke pyactionL2 --result --param topic "1"

sleep 120
wsk -i action invoke pyactionL2 --result --param topic "1"

sleep 180
wsk -i action invoke pyactionL2 --result --param topic "1"

sleep 601
wsk -i action invoke pyactionL2 --result --param topic "1"

sleep 420
wsk -i action invoke pyactionL2 --result --param topic "1"
echo "Start End"
```

##### Step 3. Analyze

When the experiment is finished, please check the OpenWhisk log to get the information about when the container is created and destroyed. The log is stored in /var/tmp/wsklog.



### How to test your code

You can directly deploy the OpenWhisk platform using your modified code. However, for better debugging, I recommend you make some unit tests in IDE before deployment.  [ShardingTest.scala](core/controller/src/main/scala/org/apache/openwhisk/core/Test/ShardingTest.scala) and [PoolTest.scala](core/controller/src/main/scala/org/apache/openwhisk/core/Test/PoolTest.scala) is the unit test I made for the Load Balancer and the ContainerPool. You can also design your test.
