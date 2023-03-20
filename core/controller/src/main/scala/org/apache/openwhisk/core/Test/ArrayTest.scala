package org.apache.openwhisk.core.Test

import java.util.concurrent.{Executors, TimeUnit}

//import org.apache.openwhisk.core.Test.EERE.lakers
//import org.apache.openwhisk.core.containerpool.WindowMap.MAP
//import org.apache.openwhisk.core.loadBalancer.histogram.NumberofActions

//import scala.concurrent.duration._
//import org.apache.openwhisk.core.loadBalancer.histogram.idleTime

//import scala.collection.mutable.ArrayBuffer

object EERE {
  var eere: Int = 9999
  var lakers = scala.collection.mutable.Map("Lebron" -> (1,3), "Rondo" -> (2,4))
}

case class message (var preWarmParameter: Int= 1,
                    var keepAliveParameter: Int= 10
)



object ArrayTest {
  def main(args: Array[String]): Unit = {
//    var i:Int = 10
//    val j:Int = 20
//    val x:Int = 20
//    var histogram=new Array[ArrayBuffer[Int]](1000)  //共有1000个action，换成可修改的变量
//    var actionName = new ArrayBuffer[String](1000) //共有1000个action
//    var idleTime = new ArrayBuffer[Int](NumberofActions) //每个action对应一个元素，用于记录上一次被调用的时刻
//    idleTime.append((System.currentTimeMillis()/60000).toInt)
//    idleTime.append(111)
//    idleTime.append(222)
//    var time : FiniteDuration = 0.1.minute
    //println(time)

      var MAP = scala.collection.mutable.Map("ContainerName" -> (0,0))
      MAP("action")=(1,0)
      //println(MAP)
      MAP("action") = (2,0)
      //print(MAP.get("action").get._1)
      if(MAP.get("action").get._1==2) {
          println(MAP.get("action"))
      }

    if (MAP.get("action1") == None) {
      MAP("action1") = (2, 2)
      println(MAP)
      var x = MAP.get("action1").get._1 + 1
      MAP("action1") = (x, 2)
      println(MAP("action1"))

    }

    println("*************")
    for(kv<-MAP)
      println(kv._1)



    val delayRun = Executors.newSingleThreadScheduledExecutor()
    //val preflag = prewarmedPool.toList

    var x = 2

    val process = new Runnable {
      def run() = {


        if(MAP.get("action1")==None){
          println("yes")
        }
        else {
          println("no")
        }
        }
      }


    delayRun.schedule(process, 5, TimeUnit.SECONDS) // 第二个参数为延时时间




    //println(idleTime.length)
/*
    //println(x*0.05)
    println(lakers.get("Lebron").get._1)
    lakers("St1") = (9,10)
    lakers("St1") = (90,11)

    println(lakers)

    var message = new message(10,9)
    function(message)
    println(message)

    var idletime = System.currentTimeMillis()/60000

    Thread.sleep(60001)

    var idletime1 = System.currentTimeMillis()/60000

    //i = idletime
    println("time: "+idletime)
    println("time: "+idletime1)

 */

  }

  def function(para:message): Unit = {
    var i:Int = 8888
    i = 9999
    para.keepAliveParameter = i
    para.preWarmParameter = 7777
  }



}


