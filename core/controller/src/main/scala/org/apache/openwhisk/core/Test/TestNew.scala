package org.apache.openwhisk.core.Test

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.testkit.TestProbe
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.connector.{MessageConsumer, MessageFeed, MessageProducer, MessagingProvider}
import org.apache.openwhisk.core.entity.{ByteSize, ControllerInstanceId, EntityName, EntityPath, ExecManifest, FullyQualifiedEntityName}
import org.apache.openwhisk.core.etcd.EtcdKV.ThrottlingKeys
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdConfig}
import org.apache.openwhisk.utils.{retry => utilRetry}

import java.nio.file.Paths

//import org.apache.openwhisk.core.database.test.DbUtils

import  org.apache.openwhisk.core.loadBalancer._

import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers, Suite}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import pureconfig.loadConfigOrThrow
import pureconfig.generic.auto._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.Try
import scala.collection.mutable.ListBuffer

trait WskActorSystem extends BeforeAndAfterAll {
  self: Suite =>
  implicit val actorSystem: ActorSystem = ActorSystem()
  implicit def executionContext: ExecutionContext = actorSystem.dispatcher
  override def afterAll() = {
    try {
      Await.result(Http().shutdownAllConnectionPools(), 30.seconds)
    } finally {
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, 30.seconds)
    }
    super.afterAll()
  }
}


@RunWith(classOf[JUnitRunner])
class FPCPoolBalancerTests1
  extends FlatSpecLike
    with Matchers
    with StreamLogging
    with ExecHelpers
    with MockFactory
    with ScalaFutures
    with WskActorSystem
    with BeforeAndAfterEach
    with DbUtils
{
  println("Hello World")
  private implicit val transId = TransactionId.testing
  implicit val ece: ExecutionContextExecutor = actorSystem.dispatcher

  val configPath  = Paths.get ("/Users/suiyifan/Downloads/openwhisk-master-copy1226/whisk.conf")
  private val etcd = EtcdClient(loadConfigOrThrow[EtcdConfig]("localhost"))



  private val testInvocationNamespace = "test-invocation-namespace"
  logging.info(this, s"XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX")

  private var httpBound: Option[Http.ServerBinding] = None

  //private val etcd = EtcdClient(loadConfigOrThrow[EtcdConfig](ConfigKeys.etcd))


  override def afterAll(): Unit = {
    httpBound.foreach(_.unbind())
    etcd.close()
    super.afterAll()
  }


  private val whiskConfig = new WhiskConfig(ExecManifest.requiredProperties)


  def feedProbe(connector: Option[TestConnector] = None) = new FeedFactory {
    def createFeed(f: ActorRefFactory, m: MessagingProvider, p: (Array[Byte]) => Future[Unit]): ActorRef =
      connector
        .map { c =>
          f.actorOf(Props {
            new MessageFeed("activeack", logging, c, 128, 1.second, p)
          })
        }
        .getOrElse(TestProbe().testActor)
  }


  private val lbConfig: ShardingContainerPoolBalancerConfig =
    loadConfigOrThrow[ShardingContainerPoolBalancerConfig](ConfigKeys.loadbalancer)

  private def fakeMessageProvider(consumer: TestConnector): MessagingProvider = {
    new MessagingProvider {
      override def getConsumer(
                                whiskConfig: WhiskConfig,
                                groupId: String,
                                topic: String,
                                maxPeek: Int,
                                maxPollInterval: FiniteDuration)(implicit logging: Logging, actorSystem: ActorSystem): MessageConsumer =
        consumer

      override def getProducer(config: WhiskConfig, maxRequestSize: Option[ByteSize])(
        implicit logging: Logging,
        actorSystem: ActorSystem): MessageProducer = consumer.getProducer()

      override def ensureTopic(config: WhiskConfig,
                               topic: String,
                               topicConfig: String,
                               maxMessageBytes: Option[ByteSize])(implicit logging: Logging): Try[Unit] = Try {}
    }
  }

  private val etcdDocsToDelete = ListBuffer[(EtcdClient, String)]()

  private def etcdPut(etcdClient: EtcdClient, key: String, value: String) = {
    etcdDocsToDelete += ((etcdClient, key))
    Await.result(etcdClient.put(key, value), 10.seconds)
  }

  override def afterEach(): Unit = {
    etcdDocsToDelete.map { etcd =>
      Try {
        Await.result(etcd._1.del(etcd._2), 10.seconds)
      }
    }
    etcdDocsToDelete.clear()
    //cleanup()
    super.afterEach()
  }

  it should "watch the throttler flag from ETCD, and keep them in memory" in {
    val mockConsumer = new TestConnector("fake", 4, true)
    val messageProvider = fakeMessageProvider(mockConsumer)

    val poolBalancer =
      new FPCPoolBalancer(whiskConfig, ControllerInstanceId("0"), etcd, feedProbe(), lbConfig, messageProvider)
    val action = FullyQualifiedEntityName(EntityPath("testns/pkg"), EntityName("action"))

    val actionKey = ThrottlingKeys.action(testInvocationNamespace, action)
    val namespaceKey = ThrottlingKeys.namespace(EntityName(testInvocationNamespace))

    Thread.sleep(1000) // wait for the watcher active

    // set the throttle flag to true for action, the checkThrottle should return true
    etcdPut(etcd, actionKey, "true")
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe true,
      10)

    // set the throttle flag to false for action, the checkThrottle should return false
    etcdPut(etcd, actionKey, "false")
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe false,
      10)

    // set the throttle flag to true for action's namespace, the checkThrottle should still return false
    etcdPut(etcd, namespaceKey, "true")
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe false,
      10)

    // delete the action throttle flag, then the checkThrottle should return true
    Await.result(etcd.del(actionKey), 10.seconds)
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe true,
      10)

    // set the throttle flag to false for action's namespace, the checkThrottle should return false
    etcdPut(etcd, namespaceKey, "false")
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe false,
      10)

    // delete the namespace throttle flag, the checkThrottle should return false
    Await.result(etcd.del(namespaceKey), 10.seconds)
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe false,
      10)
  }


}






