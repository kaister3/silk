package org.silkframework.runtime.activity

import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ForkJoinPool, ForkJoinWorkerThread, ThreadFactory}

import org.silkframework.util.StringUtils._

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.math.max

/**
 * An activity is a unit of work that can be executed in the background.
 * Implementing classes need to override the run method.
 *
 * @tparam T The type of value that is generated by this activity.
 *           Set to [[Unit]] if no values are generated.
 */
trait Activity[T] extends HasValue {

  /**
   * The name of the activity.
   * By default, the name is generated from the name of the implementing class.
   * Can be overridden in implementing classes.
   */
  def name: String = getClass.getSimpleName.undoCamelCase

  /**
   * Executes this activity.
   *
   * @param context Holds the context in which the activity is executed.
   */
  def run(context: ActivityContext[T]): Unit

  /**
   *  Can be overridden in implementing classes to allow cancellation of the activity.
   */
  def cancelExecution(): Unit = { }

  /**
    * Can be overridden in implementing classes to implement reset behaviour in addition to resetting the activity value to its initial value.
    */
  def reset(): Unit = { }

  /**
   * The initial value of this activity, if any.
   */
  def initialValue: Option[T] = None

  /**
   * Captures the bound value type.
   */
  type ValueType = T
}

/**
 * Executes activities.
 */
object Activity {

  /**
   * The fork join pool used to run activities.
   */
  val forkJoinPool: ForkJoinPool = {
    val minimumNumberOfThreads = 4
    val threadCount = max(minimumNumberOfThreads, Runtime.getRuntime.availableProcessors())
    new ForkJoinPool(threadCount, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true)
  }

  /**
    * The base path into which all activity output is logged
    */
  val loggingPath = "org.silkframework.runtime.activity"

  /**
   * Retrieves a control for an activity without executing it.
   * The [ActivityControl] instance can be used to start the execution of the activity.
   * After that it can be used to monitor the execution status as well as the current value and allows to request the cancellation of the execution.
   */
  def apply[T](activity: Activity[T]): ActivityControl[T] = {
    new ActivityExecution[T](activity)
  }

  /**
    * Whenever the returned activity is executed, generates and executes a new internal activity.
    */
  def regenerating[ActivityType <: Activity[ActivityData] : ClassTag, ActivityData](generateActivity: => ActivityType): Activity[ActivityData] = {
    new Activity[ActivityData] {
      @volatile var currentActivity: Option[ActivityType] = None
      override def name = implicitly[ClassTag[ActivityType]].runtimeClass.getSimpleName.undoCamelCase
      override def initialValue = generateActivity.initialValue
      override def run(context: ActivityContext[ActivityData]): Unit = {
        currentActivity = Some(generateActivity)
        currentActivity.get.run(context)
        currentActivity = None
      }
      override def cancelExecution() = currentActivity.foreach(_.cancelExecution())
      override def reset() = currentActivity.foreach(_.reset())
    }
  }

}



