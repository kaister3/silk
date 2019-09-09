package org.silkframework.workspace.activity.workflow

import org.silkframework.config.{Task, TaskSpec}
import org.silkframework.dataset.Dataset
import org.silkframework.entity.EntitySchema
import org.silkframework.execution.{ExecutionReport, ExecutionType, ExecutorRegistry}
import org.silkframework.runtime.activity._
import org.silkframework.util.Identifier
import org.silkframework.workspace.{Project, ProjectTask}

import scala.collection.mutable

/**
  * Created by robert on 9/21/2016.
  */
trait WorkflowExecutor[ExecType <: ExecutionType] extends Activity[WorkflowExecutionReport] {

  /** Returns the workflow task */
  protected def workflowTask: ProjectTask[Workflow]

  /** Returns a map of datasets that can replace variable datasets used as data sources in a workflow */
  protected def replaceDataSources: Map[String, Dataset]

  protected def executionContext: ExecType

  /** Returns a map of datasets that can replace variable datasets used as data sinks in a workflow */
  protected def replaceSinks: Map[String, Dataset]

  protected def currentWorkflow: Workflow = workflowTask.data

  protected def project: Project = workflowTask.project
  protected def workflowNodes: Seq[WorkflowNode] = currentWorkflow.nodes

  protected def execute[TaskType <: TaskSpec](task: Task[TaskType],
                                              inputs: Seq[ExecType#DataType],
                                              outputSchema: Option[EntitySchema])
                                             (implicit workflowRunContext: WorkflowRunContext): Option[ExecType#DataType] = {
    implicit val userContext: UserContext = workflowRunContext.userContext
    ExecutorRegistry.execute(task, inputs, outputSchema, executionContext, workflowRunContext.taskContext(task.id))
  }

  /** Return error if VariableDataset is used in output and input */
  protected def checkVariableDatasets()
                                     (implicit userContext: UserContext): Unit = {
    val variableDatasets = currentWorkflow.variableDatasets(project)
    val notCoveredVariableDatasets = variableDatasets.dataSources.filter(!replaceDataSources.contains(_))
    if (notCoveredVariableDatasets.nonEmpty) {
      throw new scala.IllegalArgumentException("No replacement for following variable datasets as data sources provided: " +
          notCoveredVariableDatasets.mkString(", "))
    }
    val notCoveredVariableSinks = variableDatasets.sinks.filter(!replaceSinks.contains(_))
    if (notCoveredVariableSinks.nonEmpty) {
      throw new scala.IllegalArgumentException("No replacement for following variable datasets as data sinks provided: " +
          notCoveredVariableSinks.mkString(", "))
    }
  }

  protected def workflow(implicit workflowRunContext: WorkflowRunContext): Workflow = workflowRunContext.workflow
}

case class WorkflowRunContext(activityContext: ActivityContext[WorkflowExecutionReport],
                              workflow: Workflow,
                              userContext: UserContext,
                              alreadyExecuted: mutable.Set[WorkflowNode] = mutable.Set()) {
  /**
    * Listeners for updates to task reports.
    * We need to hold them to prevent their garbage collection.
    */
  private var reportListeners: List[TaskReportListener] = List.empty

  /** Creates an activity context for a specific task that will be executed in the workflow.
    * Also wires the task execution report to the workflow execution report. */
  def taskContext(taskId: Identifier): ActivityContext[ExecutionReport] = {
    val contribution = workflow.effectiveWorkflowContributionByNodes(taskId)
    val projectAndTaskString = activityContext.status.projectAndTaskId.map(ids => ids.copy(ids.projectId, ids.taskId.map(_ + " -> " + taskId)))
    val taskContext = new ActivityMonitor[ExecutionReport](taskId, Some(activityContext), projectAndTaskId = projectAndTaskString, progressContribution = contribution)
    listenForTaskReports(taskId, taskContext)
    taskContext
  }

  // Creates a task report listener that will add that task report to the overall workflow report
  private def listenForTaskReports(taskId: Identifier,
                                   taskContext: ActivityMonitor[ExecutionReport]): Unit = {
    val listener = new TaskReportListener(taskId)
    taskContext.value.subscribe(listener)
    reportListeners ::= listener
  }

  /**
    * Updates the workflow execution report on each update of a task report.
    */
  private class TaskReportListener(task: Identifier) extends (ExecutionReport => Unit) {
    def apply(report: ExecutionReport): Unit = activityContext.value.synchronized {
      activityContext.value() = activityContext.value().withReport(task, report)
    }
  }

}
