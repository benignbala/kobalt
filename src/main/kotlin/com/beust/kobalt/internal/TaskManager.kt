package com.beust.kobalt.internal

import com.beust.kobalt.Args
import com.beust.kobalt.AsciiArt
import com.beust.kobalt.Plugins
import com.beust.kobalt.api.PluginTask
import com.beust.kobalt.api.Project
import com.beust.kobalt.maven.KobaltException
import com.beust.kobalt.misc.log
import com.google.common.collect.TreeMultimap
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class TaskManager @Inject constructor(val plugins: Plugins, val args: Args) {
    private val runBefore = TreeMultimap.create<String, String>()
    private val runAfter = TreeMultimap.create<String, String>()
    private val alwaysRunAfter = TreeMultimap.create<String, String>()

    /**
     * Called by plugins to indicate task dependencies defined at runtime. Keys depend on values.
     * Declare that `task1` depends on `task2`.
     */
    fun runBefore(task1: String, task2: String) {
        runBefore.put(task1, task2)
    }

    fun runAfter(task1: String, task2: String) {
        runAfter.put(task1, task2)
    }

    fun alwaysRunAfter(task1: String, task2: String) {
        alwaysRunAfter.put(task1, task2)
    }

    data class TaskInfo(val id: String) {
        constructor(project: String, task: String) : this(project + ":" + task)

        val project: String?
            get() = if (id.contains(":")) id.split(":").get(0) else null
        val task: String
            get() = if (id.contains(":")) id.split(":").get(1) else id
        fun matches(projectName: String) = project == null || project == projectName
    }

    public fun runTargets(targets: List<String>, projects: List<Project>) : Int {
        var result = 0
        projects.forEach { project ->
            val projectName = project.name!!
            val tasksByNames = hashMapOf<String, PluginTask>()
            plugins.allTasks.filter {
                it.project.name == project.name
            }.forEach {
                tasksByNames.put(it.name, it)
            }

            AsciiArt.logBox("Building project ${project.name}")

            val graph = DynamicGraph<PluginTask>()
            targets.forEach { target ->

                val ti = TaskInfo(target)
                if (ti.matches(projectName)) {
                    val task = tasksByNames.get(ti.task)
                    if (task != null && task.plugin.accept(project)) {
                        val reverseAfter = hashMapOf<String, String>()
                        alwaysRunAfter.keys().forEach { from ->
                            val tasks = alwaysRunAfter.get(from)
                            tasks.forEach {
                                reverseAfter.put(it, from)
                            }
                        }

                        //
                        // If the current target is free, add it as a single node to the graph
                        //
                        val allFreeTasks = calculateFreeTasks(tasksByNames, reverseAfter)
                        val currentFreeTask = allFreeTasks.filter {
                            TaskInfo(projectName, it.name).id == target
                        }
                        if (currentFreeTask.size() == 1) {
                            currentFreeTask.get(0).let {
                                val thisTaskInfo = TaskInfo(projectName, it.name)
                                graph.addNode(it)
                            }
                        }

                        //
                        // Add the transitive closure of the current task as edges to the graph
                        //
                        val transitiveClosure = calculateTransitiveClosure(project, tasksByNames, ti, task)
                        transitiveClosure.forEach { pluginTask ->
                            val rb = runBefore.get(pluginTask.name)
                            rb.forEach {
                                val to = tasksByNames.get(it)
                                if (to != null) {
                                    graph.addEdge(pluginTask, to)
                                } else {
                                    throw KobaltException("Should have found $it")
                                }
                            }
                        }

                        //
                        // If any of the nodes in the graph has an "alwaysRunAfter", add that edge too
                        //
                        val allNodes = arrayListOf<PluginTask>()
                        allNodes.addAll(graph.nodes)
                        allNodes.forEach { node ->
                            val other = alwaysRunAfter.get(node.name)
                            other?.forEach { o ->
                                tasksByNames.get(o)?.let {
                                    graph.addEdge(it, node)
                                }
                            }
                        }
                    }
                }
            }

            //
            // Now that we have a full graph, run it
            //
            log(2, "About to run graph:\n  ${graph.dump()}  ")

            val factory = object : IThreadWorkerFactory<PluginTask> {
                override public fun createWorkers(nodes: List<PluginTask>): List<IWorker<PluginTask>> {
                    val result = arrayListOf<IWorker<PluginTask>>()
                    nodes.forEach {
                        result.add(TaskWorker(arrayListOf(it), args.dryRun))
                    }
                    return result
                }
            }

            val executor = DynamicGraphExecutor(graph, factory)
            val thisResult = executor.run()
            if (result == 0) {
                result = thisResult
            }

        }
        return result
    }

    /**
     * Find the free tasks of the graph.
     */
    private fun calculateFreeTasks(tasksByNames: Map<String, PluginTask>, reverseAfter: HashMap<String, String>)
            : Collection<PluginTask> {
        val freeTaskMap = hashMapOf<String, PluginTask>()
        tasksByNames.keySet().forEach {
            if (! runBefore.containsKey(it) && ! reverseAfter.containsKey(it)) {
                freeTaskMap.put(it, tasksByNames.get(it)!!)
            }
        }

        return freeTaskMap.values()
    }

    /**
     * Find the transitive closure for the given TaskInfo
     */
    private fun calculateTransitiveClosure(project: Project, tasksByNames: Map<String, PluginTask>, ti: TaskInfo,
            task: PluginTask): HashSet<PluginTask> {
        log(3, "Processing ${ti.task}")

        val transitiveClosure = hashSetOf<PluginTask>()
        val seen = hashSetOf(ti.task)
        val toProcess = hashSetOf(ti)
        var done = false
        while (! done) {
            val newToProcess = hashSetOf<TaskInfo>()
            log(3, "toProcess size: " + toProcess.size())
            toProcess.forEach { target ->

                val currentTask = TaskInfo(project.name!!, target.task)
                transitiveClosure.add(tasksByNames.get(currentTask.task)!!)
                val task = tasksByNames.get(target.task)
                if (task == null) {
                    throw KobaltException("Unknown task: $target")
                } else {
                    val dependencyNames = runBefore.get(task.name)
                    dependencyNames.forEach { dependencyName ->
                        if (! seen.contains(dependencyName)) {
                            newToProcess.add(currentTask)
                            seen.add(dependencyName)
                        }
                    }

                    runBefore.get(task.name).forEach {
                        newToProcess.add(TaskInfo(project.name!!, it))
                    }
                }
            }
            done = newToProcess.isEmpty()
            toProcess.clear()
            toProcess.addAll(newToProcess)
        }

        return transitiveClosure
    }
}

class TaskWorker(val tasks: List<PluginTask>, val dryRun: Boolean) : IWorker<PluginTask> {
//    override fun compareTo(other: IWorker2<PluginTask>): Int {
//        return priority.compareTo(other.priority)
//    }

    override fun call() : TaskResult2<PluginTask> {
        if (tasks.size() > 0) {
            tasks.get(0).let {
                log(1, "========== ${it.project.name}:${it.name}")
            }
        }
        var success = true
        tasks.forEach {
            val tr = if (dryRun) TaskResult() else it.call()
            success = success and tr.success
        }
        return TaskResult2(success, tasks.get(0))
    }

//    override val timeOut : Long = 10000

    override val priority: Int = 0
}