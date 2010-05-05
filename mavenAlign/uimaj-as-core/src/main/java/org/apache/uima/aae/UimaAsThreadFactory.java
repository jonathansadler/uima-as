/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.uima.aae;

import java.util.concurrent.ThreadFactory;

import org.apache.uima.UIMAFramework;
import org.apache.uima.aae.controller.PrimitiveAnalysisEngineController;
import org.apache.uima.util.Level;
import org.springframework.core.task.TaskRejectedException;

/**
 * Custom ThreadFactory for use in the TaskExecutor. The TaskExecutor is plugged in by Spring from
 * spring xml file generated by dd2spring. The TaskExecutor is only defined for PrimitiveControllers
 * and its main purpose is to provide thread pooling and management. Each new thread produced by
 * this ThreadFactory is used to initialize a dedicated AE instance in the PrimitiveController.
 * 
 * 
 */
public class UimaAsThreadFactory implements ThreadFactory {
  
  private static final Class CLASS_NAME = UimaAsThreadFactory.class;
  
  private PrimitiveAnalysisEngineController controller;

  private ThreadGroup theThreadGroup;

  /**
   * 
   * 
   * @param tGroup
   * @param aController
   */
  public UimaAsThreadFactory(ThreadGroup tGroup, PrimitiveAnalysisEngineController aController) {
    controller = aController;
    theThreadGroup = tGroup;
  }

  public void stop() {
  }

  /**
   * Creates a new thread, initializes instance of AE via a call on a given PrimitiveController.
   * Once the thread finishes initializing AE instance in the controller, it calls run() on a given
   * Runnable. This Runnable is a Worker instance managed by the TaskExecutor. When the thread calls
   * run() on the Runnable it blocks until the Worker releases it.
   */
  public Thread newThread(final Runnable r) {
    Thread newThread = null;
    try {
      newThread = new Thread(theThreadGroup, new Runnable() {
        public void run() {
          Thread.currentThread().setName(
                  controller.getComponentName() + " Process Thread-"
                          + Thread.currentThread().getId());
          try {
            if (controller != null && !controller.threadAssignedToAE()) {
              // call the controller to initialize next instance of AE. Once initialized this
              // AE instance process() method will only be called from this thread
              controller.initializeAnalysisEngine();
            }
            // Call given Worker (Runnable) run() method and block. This call block until the
            // TaskExecutor is terminated.
            r.run();
          } catch (Exception e) {
            return;
          }
        }
      });
    } catch (Exception e) {
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
        if ( controller != null ) {
          UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                  "CacheEntry", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                  "UIMAEE_service_exception_WARNING", controller.getComponentName());
        }

        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                "CacheEntry", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_exception__WARNING", e);
      }
    }
    return newThread;
  }
}
