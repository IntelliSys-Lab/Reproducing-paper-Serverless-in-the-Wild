package org.apache.openwhisk.core.invoker


import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.duration.DurationInt

object HelloWorld {
  def main(args: Array[String]): Unit = {
    val deadline = 10.seconds.fromNow
    // do something
    Thread.sleep(100)

    val rest = deadline.timeLeft
    println("rest:"+ rest.toSeconds )


    val runnable = new Runnable {
      override def run() = {
        println("Hello !!")
      }
    }
    val service = Executors.newSingleThreadScheduledExecutor()
    // 第二个参数为首次执行的延时时间，第三个参数为定时执行的间隔时间
    service.schedule(runnable,1,TimeUnit.SECONDS)
    val service1 = Executors.newSingleThreadScheduledExecutor()
    service1.schedule(runnable,2,TimeUnit.SECONDS)
    for (i <- 0 until 12) {
      println("dfd")
      Thread.sleep(200)
    }


  }
}

