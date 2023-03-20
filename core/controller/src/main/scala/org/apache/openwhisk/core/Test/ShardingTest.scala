package org.apache.openwhisk.core.Test





import org.apache.openwhisk.common.InvokerState.{Healthy, Offline, Unhealthy}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.junit.JUnitRunner
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import scala.concurrent.duration._
import org.apache.openwhisk.common.{InvokerHealth, Logging, NestedSemaphore, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entity.{ActivationId, BasicAuthenticationAuthKey, ByteSize, ControllerInstanceId, EntityName, EntityPath, ExecManifest, FullyQualifiedEntityName, Identity, InvokerInstanceId, MemoryLimit, Namespace, Secret, Subject, UUID, WhiskActionMetaData, WhiskActivation}
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.loadBalancer._

import scala.concurrent.{Await, Future}
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem}
import akka.testkit.TestProbe
//import org.apache.openwhisk.core.Test.EERE._
import org.apache.openwhisk.core.connector.{ActivationMessage, CompletionMessage, Message, MessageConsumer, MessageProducer, MessagingProvider, ResultMetadata}
//import org.apache.openwhisk.core.loadBalancer.histogram.histogram

import java.nio.charset.StandardCharsets
import scala.collection.mutable.ArrayBuffer



/**
 * Unit tests for the ContainerPool object.
 *
 * These tests test only the "static" methods "schedule" and "remove"
 * of the ContainerPool object.
 */
@RunWith(classOf[JUnitRunner])
class ShardingContainerPoolBalancerTests
  extends FlatSpec
    with Matchers
    with StreamLogging
    with ExecHelpers
    with MockFactory {
  behavior of "ShardingContainerPoolBalancerState"

  val defaultUserMemory: ByteSize = 1024.MB

  def healthy(i: Int, memory: ByteSize = defaultUserMemory) =
    new InvokerHealth(InvokerInstanceId(i, userMemory = memory), Healthy)

  def unhealthy(i: Int) = new InvokerHealth(InvokerInstanceId(i, userMemory = defaultUserMemory), Unhealthy)

  def offline(i: Int) = new InvokerHealth(InvokerInstanceId(i, userMemory = defaultUserMemory), Offline)

  def semaphores(count: Int, max: Int): IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]] =
    IndexedSeq.fill(count)(new NestedSemaphore[FullyQualifiedEntityName](max))

  def lbConfig(blackboxFraction: Double, managedFraction: Option[Double] = None) =
    ShardingContainerPoolBalancerConfig(
      managedFraction.getOrElse(1.0 - blackboxFraction),
      blackboxFraction,
      1,
      1.minute)

  it should "update invoker's state, growing the slots data and keeping valid old data" in {
    // start empty
    val slots = 10
    val memoryPerSlot = MemoryLimit.MIN_MEMORY
    val memory = memoryPerSlot * slots
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5))
    state.invokers shouldBe 'empty
    state.blackboxInvokers shouldBe 'empty
    state.managedInvokers shouldBe 'empty
    state.invokerSlots shouldBe 'empty
    state.managedStepSizes shouldBe Seq.empty
    state.blackboxStepSizes shouldBe Seq.empty


    // apply one update, verify everything is updated accordingly
    val update1 = IndexedSeq(healthy(0, memory))
    state.updateInvokers(update1)

    state.invokers shouldBe update1
    state.blackboxInvokers shouldBe update1 // fallback to at least one
    state.managedInvokers shouldBe update1 // fallback to at least one
    state.invokerSlots should have size update1.size
    state.invokerSlots.head.availablePermits shouldBe memory.toMB
    state.managedStepSizes shouldBe Seq(1)
    state.blackboxStepSizes shouldBe Seq(1)

    // acquire a slot to alter invoker state
    state.invokerSlots.head.tryAcquire(memoryPerSlot.toMB.toInt)
    state.invokerSlots.head.availablePermits shouldBe (memory - memoryPerSlot).toMB.toInt

    // apply second update, growing the state
    val update2 =
      IndexedSeq(healthy(0, memory), healthy(1, memory * 2))
    state.updateInvokers(update2)

    state.invokers shouldBe update2
    state.managedInvokers shouldBe IndexedSeq(update2.head)
    state.blackboxInvokers shouldBe IndexedSeq(update2.last)
    state.invokerSlots should have size update2.size
    state.invokerSlots.head.availablePermits shouldBe (memory - memoryPerSlot).toMB.toInt
    state.invokerSlots(1).tryAcquire(memoryPerSlot.toMB.toInt)
    state.invokerSlots(1).availablePermits shouldBe memory.toMB * 2 - memoryPerSlot.toMB
    state.managedStepSizes shouldBe Seq(1)
    state.blackboxStepSizes shouldBe Seq(1)
  }


  it should "allow managed partition to overlap with blackbox for small N" in {
    Seq(0.1, 0.2, 0.3, 0.4, 0.5).foreach { bf =>
      val state = ShardingContainerPoolBalancerState()(lbConfig(bf))

      (1 to 100).toSeq.foreach { i =>
        state.updateInvokers((1 to i).map(_ => healthy(1, MemoryLimit.STD_MEMORY)))

        withClue(s"invoker count $bf $i:") {
          state.managedInvokers.length should be <= i
          state.blackboxInvokers should have size Math.max(1, (bf * i).toInt)

          val m = state.managedInvokers.length
          val b = state.blackboxInvokers.length
          bf match {
            // written out explicitly for clarity
            case 0.1 if i < 10 => m + b shouldBe i + 1
            case 0.2 if i < 5 => m + b shouldBe i + 1
            case 0.3 if i < 4 => m + b shouldBe i + 1
            case 0.4 if i < 3 => m + b shouldBe i + 1
            case 0.5 if i < 2 => m + b shouldBe i + 1
            case _ => m + b shouldBe i
          }
        }
      }
    }
  }

  it should "return the same pools if managed- and blackbox-pools are overlapping" in {

    val state = ShardingContainerPoolBalancerState()(lbConfig(1.0, Some(1.0)))
    (1 to 100).foreach { i =>
      state.updateInvokers((1 to i).map(_ => healthy(1, MemoryLimit.STD_MEMORY)))
    }

    state.managedInvokers should have size 100
    state.blackboxInvokers should have size 100

    state.managedInvokers shouldBe state.blackboxInvokers
  }

  it should "update the cluster size, adjusting the invoker slots accordingly" in {
    val slots = 10
    val memoryPerSlot = MemoryLimit.MIN_MEMORY
    val memory = memoryPerSlot * slots
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5))
    state.updateInvokers(IndexedSeq(healthy(0, memory), healthy(1, memory * 2)))

    state.invokerSlots.head.tryAcquire(memoryPerSlot.toMB.toInt)
    state.invokerSlots.head.availablePermits shouldBe (memory - memoryPerSlot).toMB

    state.invokerSlots(1).tryAcquire(memoryPerSlot.toMB.toInt)
    state.invokerSlots(1).availablePermits shouldBe memory.toMB * 2 - memoryPerSlot.toMB

    state.updateCluster(2)
    state.invokerSlots.head.availablePermits shouldBe memory.toMB / 2 // state reset + divided by 2
    state.invokerSlots(1).availablePermits shouldBe memory.toMB
  }

  it should "fallback to a size of 1 (alone) if cluster size is < 1" in {
    val slots = 10
    val memoryPerSlot = MemoryLimit.MIN_MEMORY
    val memory = memoryPerSlot * slots
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5))
    state.updateInvokers(IndexedSeq(healthy(0, memory)))

    state.invokerSlots.head.availablePermits shouldBe memory.toMB

    state.updateCluster(2)
    state.invokerSlots.head.availablePermits shouldBe memory.toMB / 2

    state.updateCluster(0)
    state.invokerSlots.head.availablePermits shouldBe memory.toMB

    state.updateCluster(-1)
    state.invokerSlots.head.availablePermits shouldBe memory.toMB
  }

  it should "set the threshold to 1 if the cluster is bigger than there are slots on 1 invoker" in {
    val slots = 10
    val memoryPerSlot = MemoryLimit.MIN_MEMORY
    val memory = memoryPerSlot * slots
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5))
    state.updateInvokers(IndexedSeq(healthy(0, memory)))

    state.invokerSlots.head.availablePermits shouldBe memory.toMB

    state.updateCluster(20)

    state.invokerSlots.head.availablePermits shouldBe MemoryLimit.MIN_MEMORY.toMB
  }
  val namespace = EntityPath("testspace")
  val name = EntityName("testAction1")
  val fqn = FullyQualifiedEntityName(namespace, name)

  behavior of "schedule"

  implicit val transId = TransactionId.testing

  it should "return None on an empty invoker list" in {
    ShardingContainerPoolBalancer.schedule(
      1,
      fqn,
      IndexedSeq.empty,
      IndexedSeq.empty,
      MemoryLimit.MIN_MEMORY.toMB.toInt,
      index = 0,
      step = 2) shouldBe None
  }

  it should "return None if no invokers are healthy" in {
    val invokerCount = 3
    val invokerSlots = semaphores(invokerCount, 3)
    val invokers = (0 until invokerCount).map(unhealthy)

    ShardingContainerPoolBalancer.schedule(
      1,
      fqn,
      invokers,
      invokerSlots,
      MemoryLimit.MIN_MEMORY.toMB.toInt,
      index = 0,
      step = 2) shouldBe None
  }

  it should "choose the first available invoker, jumping in stepSize steps, falling back to randomized scheduling once all invokers are full" in {
    val invokerCount = 3
    val slotPerInvoker = 3
    val invokerSlots = semaphores(invokerCount + 3, slotPerInvoker) // needs to be offset by 3 as well
    val invokers = (0 until invokerCount).map(i => healthy(i + 3)) // offset by 3 to asset InstanceId is returned

    val expectedResult = Seq(3, 3, 3, 5, 5, 5, 4, 4, 4)
    val result = expectedResult.map { _ =>
      ShardingContainerPoolBalancer
        .schedule(1, fqn, invokers, invokerSlots, 1, index = 0, step = 2)
        .get
        ._1
        .toInt
    }



    result shouldBe expectedResult

    val bruteResult = (0 to 100).map { _ =>
      ShardingContainerPoolBalancer
        .schedule(1, fqn, invokers, invokerSlots, 1, index = 0, step = 2)
        .get
    }

    bruteResult.map(_._1.toInt) should contain allOf(3, 4, 5)
    bruteResult.map(_._2) should contain only true
  }

  it should "ignore unhealthy or offline invokers" in {
    val invokers = IndexedSeq(healthy(0), unhealthy(1), offline(2), healthy(3))
    val slotPerInvoker = 3
    val invokerSlots = semaphores(invokers.size, slotPerInvoker)

    val expectedResult = Seq(0, 0, 0, 3, 3, 3)
    val result = expectedResult.map { _ =>
      ShardingContainerPoolBalancer
        .schedule(1, fqn, invokers, invokerSlots, 1, index = 0, step = 1)
        .get
        ._1
        .toInt
    }


    result shouldBe expectedResult

    // more schedules will result in randomized invokers, but the unhealthy and offline invokers should not be part
    val bruteResult = (0 to 100).map { _ =>
      ShardingContainerPoolBalancer
        .schedule(1, fqn, invokers, invokerSlots, 1, index = 0, step = 1)
        .get
    }

    bruteResult.map(_._1.toInt) should contain allOf(0, 3)
    bruteResult.map(_._1.toInt) should contain noneOf(1, 2)
    bruteResult.map(_._2) should contain only true
  }

  it should "only take invokers that have enough free slots" in {
    val invokerCount = 3
    // Each invoker has 4 slots
    val invokerSlots = semaphores(invokerCount, 4)
    val invokers = (0 until invokerCount).map(i => healthy(i))

    // Ask for three slots -> First invoker should be used
    ShardingContainerPoolBalancer
      .schedule(1, fqn, invokers, invokerSlots, 3, index = 0, step = 1)
      .get
      ._1
      .toInt shouldBe 0



    // Ask for two slots -> Second invoker should be used
    ShardingContainerPoolBalancer
      .schedule(1, fqn, invokers, invokerSlots, 2, index = 0, step = 1)
      .get
      ._1
      .toInt shouldBe 1
    // Ask for 1 slot -> First invoker should be used
    ShardingContainerPoolBalancer
      .schedule(1, fqn, invokers, invokerSlots, 1, index = 0, step = 1)
      .get
      ._1
      .toInt shouldBe 0
    // Ask for 4 slots -> Third invoker should be used
    ShardingContainerPoolBalancer
      .schedule(1, fqn, invokers, invokerSlots, 4, index = 0, step = 1)
      .get
      ._1
      .toInt shouldBe 2
    // Ask for 2 slots -> Second invoker should be used
    ShardingContainerPoolBalancer
      .schedule(1, fqn, invokers, invokerSlots, 2, index = 0, step = 1)
      .get
      ._1
      .toInt shouldBe 1

    invokerSlots.foreach(_.availablePermits shouldBe 0)
  }

  behavior of "pairwiseCoprimeNumbersUntil"

  it should "return an empty set for malformed inputs" in {
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(0) shouldBe Seq.empty
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(-1) shouldBe Seq.empty
  }

  it should "return all coprime numbers until the number given" in {
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(1) shouldBe Seq(1)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(2) shouldBe Seq(1)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(3) shouldBe Seq(1, 2)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(4) shouldBe Seq(1, 3)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(5) shouldBe Seq(1, 2, 3)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(9) shouldBe Seq(1, 2, 5, 7)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(10) shouldBe Seq(1, 3, 7)
  }


  behavior of "concurrent actions"
  it should "allow concurrent actions to be scheduled to same invoker without affecting memory slots" in {
    val invokerCount = 3
    // Each invoker has 2 slots, each action has concurrency 3
    val slots = 2
    val invokerSlots = semaphores(invokerCount, slots)
    val concurrency = 3
    val invokers = (0 until invokerCount).map(i => healthy(i))






    (0 until invokerCount).foreach { i =>
      (1 to slots).foreach { s =>
        (1 to concurrency).foreach { c =>
          ShardingContainerPoolBalancer
            .schedule(concurrency, fqn, invokers, invokerSlots, 1, 0, 1)
            .get
            ._1
            .toInt shouldBe i
          invokerSlots
            .lift(i)
            .get
            .concurrentState(fqn)
            .availablePermits shouldBe concurrency - c
        }
      }
    }

  }

  val config = new WhiskConfig(ExecManifest.requiredProperties)
  val invokerMem = 2000.MB
  val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean)
  val concurrency = if (concurrencyEnabled) 3 else 1 //openwhisk默认不允许并行,修改application.conf 的 concurrencyLimit
  val actionMem = 256.MB

  //初始化histogram
  val NumberofActions = 1000
  val Bounds = 240 //histogram的上界是4小时
  var histogram = new Array[ArrayBuffer[Int]](NumberofActions) //共有1000个action
  var actionName = new ArrayBuffer[String](NumberofActions) //共有1000个action
  var idleTime = new ArrayBuffer[Int](NumberofActions)   //每个action对应一个元素，用于记录上一次被调用的时刻
  var idleTimeTotalNumber = new ArrayBuffer[Int](NumberofActions)   //每个action对应一个元素，用于该action被调用的总次数
  var Window = scala.collection.mutable.Map("ActionrName" -> (0,10))

  //0号位元素不使用，仅占用
  actionName.append("StartRecordingAction")
  idleTime.append(0)
  idleTimeTotalNumber.append(0)


  //histogram初始化，共有1000个arraybuffer，每个buffer含有240个元素（对应IT从0~239）
  initializeHistogram()



  var actionMetaData =
    WhiskActionMetaData(
      namespace,
      name,
      jsMetaData(Some("jsMain"), false),
      limits = actionLimits(actionMem, concurrency))

  val actionMetaDataNew =
    WhiskActionMetaData(
      EntityPath("testspace2"),
      EntityName("testname2"),
      jsMetaData(Some("jsMain"), false),
      limits = actionLimits(actionMem, concurrency))

  val maxContainers = invokerMem.toMB.toInt / actionMetaData.limits.memory.megabytes
  val numInvokers = 3
  val maxActivations = maxContainers * numInvokers * concurrency




  //run a separate test for each variant of 1..n concurrently-ish arriving activations, to exercise:
  // - no containers started
  // - containers started but no concurrency room
  // - no concurrency room and no memory room to launch new containers
  //(1 until maxActivations).foreach { i =>
  (50 until maxActivations).foreach { i =>
    it should s"reflect concurrent processing $i state in containerSlots" in {
      //each batch will:
      // - submit activations concurrently
      // - wait for activation submission to messaging system (mostly to detect which invoker was assiged
      // - verify remaining concurrency slots available
      // - complete activations concurrently
      // - verify concurrency/memory slots are released
      testActivationBatch(i-59)

      Thread.sleep(scala.util.Random.nextInt(500)) //随机暂停0-500ms

    }
  }

  def mockMessaging(): MessagingProvider = {
    val messaging = stub[MessagingProvider]
    val producer = stub[MessageProducer]
    val consumer = stub[MessageConsumer]
    (messaging
      .getProducer(_: WhiskConfig, _: Option[ByteSize])(_: Logging, _: ActorSystem))
      .when(*, *, *, *)
      .returns(producer)
    (messaging
      .getConsumer(_: WhiskConfig, _: String, _: String, _: Int, _: FiniteDuration)(_: Logging, _: ActorSystem))
      .when(*, *, *, *, *, *, *)
      .returns(consumer)
    (producer
      .send(_: String, _: Message, _: Int))
      .when(*, *, *)
      .returns(Future.successful(ResultMetadata("fake", 0, 0)))

    messaging
  }

  def testActivationBatch(numActivations: Int): Unit = {
    //setup mock messaging
    val feedProbe = new FeedFactory {
      def createFeed(f: ActorRefFactory, m: MessagingProvider, p: (Array[Byte]) => Future[Unit]) =
        TestProbe().testActor

    }
    val invokerPoolProbe = new InvokerPoolFactory {
      override def createInvokerPool(
                                      actorRefFactory: ActorRefFactory,
                                      messagingProvider: MessagingProvider,
                                      messagingProducer: MessageProducer,
                                      sendActivationToInvoker: (MessageProducer, ActivationMessage, InvokerInstanceId) => Future[ResultMetadata],
                                      monitor: Option[ActorRef]): ActorRef =
        TestProbe().testActor
    }
    val balancer =
      new ShardingContainerPoolBalancer(config, ControllerInstanceId("0"), feedProbe, invokerPoolProbe, mockMessaging)

    val invokers = IndexedSeq.tabulate(numInvokers) { i =>
      new InvokerHealth(InvokerInstanceId(i, userMemory = invokerMem), Healthy)
    }
    balancer.schedulingState.updateInvokers(invokers)
    val invocationNamespace = EntityName("invocationSpace")

    if(numActivations%2==0){
      actionMetaData =
        WhiskActionMetaData(
          EntityPath("testspace2"),
          EntityName("testAction22"),
          jsMetaData(Some("jsMain"), false),
          limits = actionLimits(actionMem, concurrency))
    }
    else{
      actionMetaData =
        WhiskActionMetaData(
          EntityPath("testspace1"),
          EntityName("testAction11"),
          jsMetaData(Some("jsMain"), false),
          limits = actionLimits(actionMem, concurrency))
    }

    val fqn = actionMetaData.fullyQualifiedName(true)
    val hash =
      ShardingContainerPoolBalancer.generateHash(invocationNamespace, actionMetaData.fullyQualifiedName(false))
    val home = hash % invokers.size
    val stepSizes = ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(invokers.size)
    val stepSize = stepSizes(hash % stepSizes.size)
    val uuid = UUID()

    HistogramPolicy(actionMetaData)

    //initiate activation
    val published = (0 until numActivations).map { _ =>               //publish里包含很多个activation，用于测试action的并行处理能力。每个id对应一个activation的id。
      val aid = ActivationId.generate()
      val msg = ActivationMessage(
        TransactionId.testing,
        actionMetaData.fullyQualifiedName(true),
        actionMetaData.rev,
        Identity(Subject(), Namespace(invocationNamespace, uuid), BasicAuthenticationAuthKey(uuid, Secret())),
        aid,
        Window.get(actionMetaData.fullyQualifiedName(true).toString).get._1,   //HistogramPolicy(actionMetaData)._1, //pre-warm parameter
        Window.get(actionMetaData.fullyQualifiedName(true).toString).get._2 ,  //HistogramPolicy(actionMetaData)._2, //keep-alive parameter
        ControllerInstanceId("0"),
        blocking = false,
        content = None,
        initArgs = Set.empty,
        lockedArgs = Map.empty)

      logging.info(
        this,
        s"ActionName: '${actionMetaData}, PreWarmWindow parameter:  ${msg.preWarmParameter}, KeepAliveWindow:  '${msg.keepAliveParameter} ")

      //send activation to loadbalancer
      aid -> balancer.publish(actionMetaData.toExecutableWhiskAction.get, msg)

    }.toMap

    val activations = published.values
    val ids = published.keys

    println("action's fqn:"+actionMetaData.fullyQualifiedName(true))
    println("action's Name:"+actionMetaData.fullyQualifiedName(true))
    println("action's Namespace:"+actionMetaData.namespace)
    if(actionMetaData.namespace.toString=="testspace"){
      println("yes")
    }
    else{
      println("no"+actionMetaData.namespace)
    }

    //println("activationID:"+ActivationId.generate())  //这个才是真正的activationId生成方式，是32位的UUId！
    //println("activationID:")
    println("WskActivation:"+WhiskActivation)
    println("time"+System.currentTimeMillis())
    val acty=RecordNewAction(actionMetaData)
    println("actionType: "+acty+"  "+actionName(acty-1))


    //println(HistogramPolicy(actionMetaData)._2) //输出第2个window(keep-alive parameter)
    /*
    for (i <- 0 until 240) {
     print(histogram(acty)(i))
    }
     */

    /*思路：
    目前，我们需要关注的有两个事情：1.每个action的名称（FullyQualifiedEntityName），用于绘制直方图；2.每个action两次activation间的调用间隔（Idle Time），用于绘制直方图。
    这两个我们都得到了，下面需要为每个action，设定一个表格，存储其直方图

    关于ActionMetaData:用于定义具体的action，每个action都有其name（FullyQualifiedEntityName）、namespace，根据这些（fqn），就可以得到唯一的action。
    LB中的action，就是指一个WhiskActionMetaData

    hybrid policy:
    先在ActivationMessage（msg）中，根据ActionMetaData，获取其直方图，然后再修改msg。

     */



    //wait for activation submissions
    Await.ready(Future.sequence(activations.toList), 10.seconds)

    val maxActivationsPerInvoker = concurrency * maxContainers
    //verify updated concurrency slots

    def rem(count: Int) =
      if (count % concurrency > 0) {
        concurrency - (count % concurrency)
      } else {
        0
      }

    //assert available permits per invoker are as expected
    var nextInvoker = home
    ids.toList.grouped(maxActivationsPerInvoker).zipWithIndex.foreach { g =>
      val remaining = rem(g._1.size)
      val concurrentState = balancer.schedulingState._invokerSlots
        .lift(nextInvoker)
        .get
        .concurrentState(fqn)
      concurrentState.availablePermits shouldBe remaining
      concurrentState.counter shouldBe g._1.size
      nextInvoker = (nextInvoker + stepSize) % numInvokers
    }

    //complete all
    val acks = ids.map { aid =>
      val invoker = balancer.activationSlots(aid).invokerName
      completeActivation(invoker, balancer, aid)
    }

    Await.ready(Future.sequence(acks), 10.seconds)


    //verify invokers go back to unused state
    invokers.foreach { i =>
      val concurrentState = balancer.schedulingState._invokerSlots
        .lift(i.id.toInt)
        .get
        .concurrentState
        .get(fqn)

      concurrentState shouldBe None
      balancer.schedulingState._invokerSlots.lift(i.id.toInt).map { i =>
        i.availablePermits shouldBe invokerMem.toMB
      }

    }

  }

  def completeActivation(invoker: InvokerInstanceId, balancer: ShardingContainerPoolBalancer, aid: ActivationId) = {
    //complete activation
    val ack =
      CompletionMessage(TransactionId.testing, aid, Some(false), invoker).serialize.getBytes(StandardCharsets.UTF_8)
    balancer.processAcknowledgement(ack)

  }

  def printmsg(msg:ActivationMessage): Unit = {
    //println("msg":msg.keepAliveParameter)
  }


  //初始化histogram：共有1000个arraybuffer，每个buffer含有240个元素（对应IT从0~239）
  def initializeHistogram(): Unit = {
    if (histogram(1) == null) {
      for (i <- 0 until histogram.length) {
        histogram(i) = new ArrayBuffer[Int]()
        for (j <- 0 until Bounds) {
          histogram(i).append(0)
        }
      }
    }
  }

  //Histogram Policy
  def HistogramPolicy(actionMetaData: WhiskActionMetaData): (Int, Int) = {
    println("THIS IS ACTION:  " + actionMetaData.fullyQualifiedName(true) + "     's histogram")
    val actionType = RecordNewAction(actionMetaData)
    var idletime = (System.currentTimeMillis() / 60000).toInt - idleTime(actionType) //记录本次IT并更新
    println("IDLETIME: " + idletime)
    if (idletime < Bounds) {
      idleTime(actionType) = (System.currentTimeMillis() / 60000).toInt

      //初始化pre-warm和keep-alive window
      var preWarmWindow = 100000
      var keepAliveWindow = 0

      //下面记录histogram二维数组的第actionType个arraybuffer，作为该action的histogram。
      histogram(actionType)(idletime) = histogram(actionType)(idletime) + 1 //这里后续需要调整，除以10是因为用的毫秒来模拟分钟，正常是不需要的
      idleTimeTotalNumber(actionType) = idleTimeTotalNumber(actionType) + 1 //该action总被调用次数+1

      //下面计算pre-warn和keep-alive window
      if (idleTimeTotalNumber(actionType) <= 10) { //次数<=10时，使用第二种policy(a standard keep-alive approach):
        //pre-warming window = 0; keep-alive window = range of the histogram
        preWarmWindow = 0
        for (i <- 0 until histogram(actionType).length) {
          if (histogram(actionType)(i) > 0) {
            keepAliveWindow = i
          }
        }
      }

      if (idleTimeTotalNumber(actionType) > 10) { //次数>10时，使用第一种policy(Range-limited histogram):
        //pre-warming window = 5%*总数; keep-alive window = 99%*总数
        var preWarmFlag = (idleTimeTotalNumber(actionType).toInt * 0.05).toInt + 1 //第5%个非0元素，其对应的总数就是pre-warm-window. 注意toInt的使用！不然会有小数
        var flagPre = 0
        var flagPrePrevious = 0

        var keepAliveFlag = (idleTimeTotalNumber(actionType).toInt * 0.99).toInt - 1 //第99%个非0元素，其对应的总数就是keep-alive-window
        var flagKeep = 0
        var flagKeepPrevious = 0

        for (i <- 0 until histogram(actionType).length) {
          if (histogram(actionType)(i) > 0) {
            flagPrePrevious = flagPre
            flagKeepPrevious = flagKeep
            flagPre = flagPre + histogram(actionType)(i) //每个IT有几个值，就要加几
            flagKeep = flagKeep + histogram(actionType)(i)
          }
          if (flagPre >= preWarmFlag & flagPrePrevious < preWarmFlag) { //找到flagPre第一次超过preWarmFlag（5%分位）的时刻，即
            //上一次还没超过，这一次超过
            preWarmWindow = i
          }
          if (flagKeep >= keepAliveFlag & flagKeepPrevious < keepAliveFlag) { //找到flagKeep第一次超过keepAliveFlag（99%分位）的时刻，即
            //上一次还没超过，这一次超过
            keepAliveWindow = i
          }
        }
      }

      println("WINDOWS: " + preWarmWindow + " ;" + keepAliveWindow)
      Window(actionMetaData.fullyQualifiedName(true).toString) = (preWarmWindow, keepAliveWindow)
      (preWarmWindow, keepAliveWindow)
    }
    else {
      //本来该用ARIMR算法来预测（auto-arimr）
      println("This invocation's idle time is Out-Of-Bound")
      (0, 1)
    }

  }


  //记录新出现的Action
  def RecordNewAction(actionMetaData: WhiskActionMetaData): Int = {

    var ActionNameRecord = -1
    //判断该action是否是第一次出现
    for (i <- 0 until actionName.length) {
      if (actionName(i) == actionMetaData.fullyQualifiedName(true).toString) { //该action已经被记录在actionName中了
        //记得要toString！fqn的类型不是String
        ActionNameRecord = i
      }
    }

    if (ActionNameRecord == -1) { //说明该action之前没出现过，需要记录在actionName中
      ActionNameRecord = actionName.length //ActionNameRecord表示该action在数组中的位置
      actionName.append(actionMetaData.fullyQualifiedName(true).toString)
      idleTime.append((System.currentTimeMillis() / 60000).toInt) //记录该action初次出现的时刻
      println(idleTime)
      idleTimeTotalNumber.append(0) //该action初次被调用，初始化该元素位置上的idleTimeNumber
    }
    ActionNameRecord
  }


  def getPreWarmWindow(PrewarmWindow:Int): Int = {
    PrewarmWindow
  }

  def getKeepAliveWindow(KeepALiveWindow: Int): Int = {
    KeepALiveWindow
  }

  //case class WindowInfo(preWarmWindow:Int,keepAliveWindow:Int)  //存储window的结果
}

