/*
 * The MIT License
 *
 * Copyright (c) 2004-2011, Sun Microsystems, Inc., Frederik Fromm
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.buildblocker;

import hudson.EnvVars;
import hudson.matrix.MatrixConfiguration;
import hudson.model.TaskListener;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.AbstractProject;
import hudson.model.queue.SubTask;
import hudson.util.RunList;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * This class represents a monitor that checks all running jobs if
 * one of their names matches with one of the given blocking job's
 * regular expressions.
 *
 * The first hit returns the blocking job's name.
 */
public class BlockingJobsMonitor {
	
	private static final Logger LOGGER = Logger.getLogger(BlockingJobsMonitor.class.getName());

    /**
     * the list of regular expressions from the job configuration
     */
    private List<String> blockingJobs;

    /**
     * Constructor using the job configuration entry for blocking jobs
     * @param blockingJobs line feed separated list og blocking jobs
     */
    public BlockingJobsMonitor(String blockingJobs) {
        if(StringUtils.isNotBlank(blockingJobs)) {
            this.blockingJobs = Arrays.asList(blockingJobs.split("\n"));
        }
    }

    /**
     * Returns the name of the first blocking job. If not found, it returns null.
     * @param item The queue item for which we are checking whether it can run or not.
     *        or null if we are not checking a job from the queue (currently only used by testing).
     * @return the name of the first blocking job.
     */
    public SubTask getBlockingJob(Queue.Item item, String params) {
        if(this.blockingJobs == null) {
            return null;
        }

		if (null == item) {
			return null;
		}
        
        Computer[] computers = Jenkins.getInstance().getComputers();

        for (Computer computer : computers) {
            List<Executor> executors = computer.getExecutors();

            executors.addAll(computer.getOneOffExecutors());

            for (Executor executor : executors) {
                if(executor.isBusy()) {
                    Queue.Executable currentExecutable = executor.getCurrentExecutable();

                    SubTask subTask = currentExecutable.getParent();
                    Queue.Task task = subTask.getOwnerTask();

                    if (task instanceof MatrixConfiguration) {
                        task = ((MatrixConfiguration) task).getParent();
                    }

                    AbstractProject project = (AbstractProject) task;

					for (String blockingJob : this.blockingJobs) {
						try {
							if (task.getName().matches(blockingJob)) {
								RunList<Run> builds = project.getBuilds();
								int i = 0;
								Iterator<Run> it = builds.iterator();
								while ((i < 10) && (it.hasNext())) {
									Run run = it.next();
									i++;
									if (run.isBuilding()) {
										try {
											TaskListener listener = new StreamTaskListener(run.getLogFile());
											EnvVars vars = run.getEnvironment(listener);
											String expandedParams = vars.expand(params);
											Map<String, String> parsedParams = parseParams(expandedParams);
											String parameters = item.getParams();
											if (null != parameters && !"".equals(parameters)) {
												if (hasBlockingParam(parsedParams, parameters)) {
													return subTask;
												}
											} else {
												return subTask;
											}
										} catch (IOException e) {
											LOGGER.warning("IOException: " + e.getMessage());
											return null;
										} catch (InterruptedException e) {
											//
										}
									}
								}
							}
						} catch (java.util.regex.PatternSyntaxException pse) {
							return null;
						}
					}
                }
            }
        }

        /**
         * check the list of items that have
         * already been approved for building
         * (but haven't actually started yet)
         */
        List<Queue.BuildableItem> buildableItems
            = Jenkins.getInstance().getQueue().getBuildableItems();

        Iterator<Queue.BuildableItem> it0 = buildableItems.iterator();
        
		while (it0.hasNext()) {
			Queue.BuildableItem buildableItem = it0.next();
			if (item != buildableItem)
				for (String blockingJob : this.blockingJobs)
					if (buildableItem.task.getFullDisplayName().matches(blockingJob)) {
						AbstractProject project = (AbstractProject) buildableItem.task;
						RunList<Run> builds = project.getBuilds();
						int i = 0;
						Iterator<Run> it = builds.iterator();
						while ((i < 10) && (it.hasNext())) {
							Run run = it.next();
							i++;
							if (run.isBuilding()) {
								try {
									TaskListener listener = new StreamTaskListener(run.getLogFile());
									EnvVars vars = run.getEnvironment(listener);
									String expandedParams = vars.expand(params);
									Map<String, String> parsedParams = parseParams(expandedParams);
									String parameters = item.getParams();
									if (null != parameters && !"".equals(parameters)) {
										if (hasBlockingParam(parsedParams, parameters)) {
											return buildableItem.task;
										}
									} else {
										return buildableItem.task;
									}
								} catch (IOException e) {
									LOGGER.warning("IOException: " + e.getMessage());
									return null;
								} catch (InterruptedException e) {
									//
								}
							}
						}
					}
		}
        return null;
    }
        
        
	private boolean hasBlockingParam(Map<String, String> blocked, String params) {
		if ((null != params) && (!"".equalsIgnoreCase(params))) {
			String[] splitted = params.split("\n");
			for (int i = 0; i < splitted.length; i++) {
				if ((null != splitted[i + 1]) && (!"".equals(splitted[i + 1])) && (splitted[i + 1].contains("="))) {
					String key = splitted[i + 1].split("=")[0];
					String value = splitted[i + 1].split("=")[1].replace("'", "");
					if (blocked.containsKey(key)) {
						return blocked.get(key).equals(value);
					}
				}
			}
		}
		return (blocked == null) || (blocked.isEmpty());
	}

	private Map<String, String> parseParams(String blockingParams) {
		if ((null != blockingParams) && (!"".equalsIgnoreCase(blockingParams))) {
			String[] paramArray = blockingParams.split(",");
			Map<String, String> params = new HashMap<String, String>(paramArray.length);
			for (String param : paramArray) {
				String[] parsed = param.split("=");
				if (2 == parsed.length) {
					params.put(parsed[0], parsed[1]);
				}
			}
			return params;
		}
		return Collections.emptyMap();
	}
}
