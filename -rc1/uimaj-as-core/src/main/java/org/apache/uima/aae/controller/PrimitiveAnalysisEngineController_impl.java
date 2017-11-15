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

package org.apache.uima.aae.controller;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;

import org.apache.uima.UIMAFramework;
import org.apache.uima.aae.AsynchAECasManager;
import org.apache.uima.aae.InProcessCache;
import org.apache.uima.aae.InProcessCache.CacheEntry;
import org.apache.uima.aae.UIMAEE_Constants;
import org.apache.uima.aae.UimaClassFactory;
import org.apache.uima.aae.controller.LocalCache.CasStateEntry;
import org.apache.uima.aae.error.AsynchAEException;
import org.apache.uima.aae.error.ErrorContext;
import org.apache.uima.aae.error.ErrorHandler;
import org.apache.uima.aae.jmx.JmxManagement;
import org.apache.uima.aae.jmx.PrimitiveServiceInfo;
import org.apache.uima.aae.jmx.ServicePerformance;
import org.apache.uima.aae.message.AsynchAEMessage;
import org.apache.uima.aae.message.MessageContext;
import org.apache.uima.aae.monitor.Monitor;
import org.apache.uima.aae.monitor.statistics.AnalysisEnginePerformanceMetrics;
import org.apache.uima.aae.spi.transport.UimaMessage;
import org.apache.uima.aae.spi.transport.UimaTransport;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.analysis_engine.AnalysisEngineManagement;
import org.apache.uima.analysis_engine.CasIterator;
import org.apache.uima.analysis_engine.metadata.AnalysisEngineMetaData;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.impl.CASImpl;
import org.apache.uima.cas.impl.OutOfTypeSystemData;
import org.apache.uima.collection.CollectionReaderDescription;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.ResourceSpecifier;
import org.apache.uima.resource.metadata.ConfigurationParameter;
import org.apache.uima.resource.metadata.impl.ConfigurationParameter_impl;
import org.apache.uima.util.Level;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

public class PrimitiveAnalysisEngineController_impl extends BaseAnalysisEngineController implements
        PrimitiveAnalysisEngineController {
  private static final Class CLASS_NAME = PrimitiveAnalysisEngineController_impl.class;
  private static final String DUMP_HEAP_THRESHOLD = "dumpHeapThreshold";
  
  // Stores AE metadata
  private AnalysisEngineMetaData analysisEngineMetadata;

  // Number of AE instances
  private int analysisEnginePoolSize;

  // Mutex
  protected Object notifyObj = new Object();

  // Temp list holding instances of AE
  private List aeList = new ArrayList();

  // Stores service info for JMX
  private PrimitiveServiceInfo serviceInfo = null;

  // Pool containing instances of AE. The default implementation provides Thread affinity
  // meaning each thread executes the same AE instance.
  protected AnalysisEngineInstancePool aeInstancePool = null;

  private String abortedCASReferenceId = null;
  // Create a shared semaphore to serialize creation of AE instances.
  // There is a single instance of this semaphore per JVM and it
  // guards uima core code that is not thread safe.
  private static Semaphore sharedInitSemaphore = new Semaphore(1);

  static private Object threadDumpMonitor = new Object();
  static private Long lastDump = Long.valueOf(0);
  private XStream xstream = new XStream(new DomDriver());

  // 6 args
  public PrimitiveAnalysisEngineController_impl(String anEndpointName,
          String anAnalysisEngineDescriptor, AsynchAECasManager aCasManager,
          InProcessCache anInProcessCache, int aWorkQueueSize, int anAnalysisEnginePoolSize)
          throws Exception {
    this(null, anEndpointName, anAnalysisEngineDescriptor, aCasManager, anInProcessCache,
            aWorkQueueSize, anAnalysisEnginePoolSize, 0);
  }

  // 7 args - adds parentController at beginning
  public PrimitiveAnalysisEngineController_impl(AnalysisEngineController aParentController,
          String anEndpointName, String anAnalysisEngineDescriptor, AsynchAECasManager aCasManager,
          InProcessCache anInProcessCache, int aWorkQueueSize, int anAnalysisEnginePoolSize)
          throws Exception {
    this(aParentController, anEndpointName, anAnalysisEngineDescriptor, aCasManager,
            anInProcessCache, aWorkQueueSize, anAnalysisEnginePoolSize, 0);
  }

  // 8 args - adds componentCasPoolSize at end
  public PrimitiveAnalysisEngineController_impl(AnalysisEngineController aParentController,
          String anEndpointName, String anAnalysisEngineDescriptor, AsynchAECasManager aCasManager,
          InProcessCache anInProcessCache, int aWorkQueueSize, int anAnalysisEnginePoolSize,
          int aComponentCasPoolSize) throws Exception {
    this(aParentController, anEndpointName, anAnalysisEngineDescriptor, aCasManager,
            anInProcessCache, aWorkQueueSize, anAnalysisEnginePoolSize, aComponentCasPoolSize, null);
  }

  // 9 args - adds initialCasHeapSize
  // ************* USED BY DD2SPRING *************
  public PrimitiveAnalysisEngineController_impl(AnalysisEngineController aParentController,
          String anEndpointName, String anAnalysisEngineDescriptor, AsynchAECasManager aCasManager,
          InProcessCache anInProcessCache, int aWorkQueueSize, int anAnalysisEnginePoolSize,
          int aComponentCasPoolSize, long anInitialCasHeapSize) throws Exception {
    this(aParentController, anEndpointName, anAnalysisEngineDescriptor, aCasManager,
            anInProcessCache, aWorkQueueSize, anAnalysisEnginePoolSize, aComponentCasPoolSize,
            anInitialCasHeapSize, null);
  }

  // 9 args - repl initialCasHeapSize with jmxManagement
  public PrimitiveAnalysisEngineController_impl(AnalysisEngineController aParentController,
          String anEndpointName, String anAnalysisEngineDescriptor, AsynchAECasManager aCasManager,
          InProcessCache anInProcessCache, int aWorkQueueSize, int anAnalysisEnginePoolSize,
          int aComponentCasPoolSize, JmxManagement aJmxManagement) throws Exception {
    this(aParentController, anEndpointName, anAnalysisEngineDescriptor, aCasManager,
            anInProcessCache, aWorkQueueSize, anAnalysisEnginePoolSize, aComponentCasPoolSize, 0,
            aJmxManagement);
  }

  // 10 args - adds initialCasHeapSize back
  public PrimitiveAnalysisEngineController_impl(AnalysisEngineController aParentController,
          String anEndpointName, String anAnalysisEngineDescriptor, AsynchAECasManager aCasManager,
          InProcessCache anInProcessCache, int aWorkQueueSize, int anAnalysisEnginePoolSize,
          int aComponentCasPoolSize, long anInitialCasHeapSize, JmxManagement aJmxManagement)
          throws Exception {
    super(aParentController, aComponentCasPoolSize, anInitialCasHeapSize, anEndpointName,
            anAnalysisEngineDescriptor, aCasManager, anInProcessCache, null, aJmxManagement);
    analysisEnginePoolSize = anAnalysisEnginePoolSize;
  }

  // 8 args - drops componentCasPoolSize and initialCasHeapSize
  public PrimitiveAnalysisEngineController_impl(AnalysisEngineController aParentController,
          String anEndpointName, String anAnalysisEngineDescriptor, AsynchAECasManager aCasManager,
          InProcessCache anInProcessCache, int aWorkQueueSize, int anAnalysisEnginePoolSize,
          JmxManagement aJmxManagement) throws Exception {
    this(aParentController, anEndpointName, anAnalysisEngineDescriptor, aCasManager,
            anInProcessCache, aWorkQueueSize, anAnalysisEnginePoolSize, 0, aJmxManagement);
  }

  public int getAEInstanceCount() {
    return analysisEnginePoolSize;
  }

  public void initializeAnalysisEngine() throws ResourceInitializationException {
    ResourceSpecifier rSpecifier = null;
    
    try {
      //  Acquire single-permit semaphore to serialize instantiation of AEs.
      //  This is done to control access to non-thread safe structures in the
      //  core. The sharedInitSemaphore is a static and is shared by all instances
      //  of this class. 
      sharedInitSemaphore.acquire();
      // Parse the descriptor in the calling thread.
      rSpecifier = UimaClassFactory.produceResourceSpecifier(super.aeDescriptor);
      AnalysisEngine ae = UIMAFramework.produceAnalysisEngine(rSpecifier, paramsMap);
        //  Call to produceAnalysisEngine() may take a long time to complete. While this
        //  method was executing, the service may have been stopped. Before continuing 
        //  check if the service has been stopped. If so, destroy AE instance and return.
        if ( isStopped() ) {
          ae.destroy();
          return;
        }
        if (aeInstancePool == null) {
          aeInstancePool = new AnalysisEngineInstancePoolWithThreadAffinity();//analysisEnginePoolSize);
        }
        if (analysisEngineMetadata == null) {
          analysisEngineMetadata = ae.getAnalysisEngineMetaData();
        }
        //  Check if OperationalProperties allow replication of this AE. Throw exception if
        //  the deployment descriptor says to scale the service *but* the AE descriptor's 
        //  OperationalProperties disallow it.
        if ( !analysisEngineMetadata.getOperationalProperties().isMultipleDeploymentAllowed() &&
             aeInstancePool.size() >= 1 ) {
          throw new ResourceInitializationException( UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                  "UIMAEE_multiple_deployment_not_allowed__WARNING", new Object[] {this.getComponentName(), ae.getMetaData().getName()});
        }
        aeInstancePool.checkin(ae);
        if (aeInstancePool.size() == analysisEnginePoolSize) {
          try {
            postInitialize();
          } catch (Exception e) {
            if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
              UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                      "initializeAnalysisEngine", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                      "UIMAEE_service_exception_WARNING", getComponentName());

              UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                      "initializeAnalysisEngine", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                      "UIMAEE_exception__WARNING", e);
            }
            throw new ResourceInitializationException(e);
          }
        }
    } catch (Exception e) {
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                "initializeAnalysisEngine", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_service_exception_WARNING", getComponentName());

        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                "initializeAnalysisEngine", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_exception__WARNING", e);
      }
      super.notifyListenersWithInitializationStatus(e);
      if (isTopLevelComponent()) {
        super.notifyListenersWithInitializationStatus(e);
      } else {
        // get the top level controller to notify
        AnalysisEngineController controller = this.getParentController();
        while (!controller.isTopLevelComponent()) {
          controller = controller.getParentController();
        }
        getParentController().notifyListenersWithInitializationStatus(e);
      }

      throw new ResourceInitializationException(e);
    } finally {
      //  Release the shared semaphore so that another instance of this class can instantiate
      //  an Analysis Engine.
      sharedInitSemaphore.release();
    }
    
  }

  public boolean threadAssignedToAE() {
    if (aeInstancePool == null) {
      return false;
    }

    return aeInstancePool.exists();
  }

  public void initialize() throws AsynchAEException {
  }

  /**
   * This method is called after all AE instances initialize. It is called once. It initializes
   * service Cas Pool, notifies the deployer that initialization completed and finally loweres a
   * semaphore allowing messages to be processed.
   * 
   * @throws AsynchAEException
   */
  private void postInitialize() throws AsynchAEException {
    try {
      if (errorHandlerChain == null) {
        super.plugInDefaultErrorHandlerChain();
      }
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.CONFIG)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.CONFIG, getClass().getName(), "initialize",
                UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_primitive_ctrl_init_info__CONFIG", new Object[] { analysisEnginePoolSize });
      }

      if (serviceInfo == null) {
        serviceInfo = new PrimitiveServiceInfo(isCasMultiplier(), this);
      }
      serviceInfo.setServiceKey(delegateKey);
      serviceInfo.setAnalysisEngineInstanceCount(analysisEnginePoolSize);

      if (!isStopped()) {
        getMonitor().setThresholds(getErrorHandlerChain().getThresholds());
        // Initialize Cas Manager
        if (getCasManagerWrapper() != null) {
          try {
        	  // Below should always be true. In spring context file AsynchAECasManager_impl
        	  // is instantiated and setCasPoolSize() method is called which sets the 
        	  // initialized state = true. isInitialized() returning true just means that
        	  // setCasPoolSize() was called.
            if (getCasManagerWrapper().isInitialized()) {
              getCasManagerWrapper().addMetadata(getAnalysisEngineMetadata());
              if (isTopLevelComponent()) {
                getCasManagerWrapper().initialize("PrimitiveAEService");
                CAS cas = getCasManagerWrapper().getNewCas("PrimitiveAEService");
                cas.release();
              }
            }
            if (isTopLevelComponent()) {
              // add delay to allow controller listener to plug itself in
              synchronized(this) {
                try {
                  this.wait(100);
                } catch(Exception exx) {}
              }
              
              super.notifyListenersWithInitializationStatus(null);
            } 

            // All internal components of this Primitive have been initialized. Open the latch
            // so that this service can start processing requests.
            latch.openLatch(getName(), isTopLevelComponent(), true);

          } catch (Exception e) {
            if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
              UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                      "postInitialize", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                      "UIMAEE_service_exception_WARNING", getComponentName());

              UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                      "postInitialize", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                      "UIMAEE_exception__WARNING", e);
            }
            throw new AsynchAEException(e);
          }
        } else {
          if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.CONFIG)) {
            UIMAFramework.getLogger(CLASS_NAME).logrb(Level.CONFIG, getClass().getName(),
                    "postInitialize", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                    "UIMAEE_cas_manager_wrapper_notdefined__CONFIG", new Object[] {});
          }
        }
        if (!isStopped()){
          if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.INFO)) {
            UIMAFramework.getLogger(CLASS_NAME).logrb(Level.INFO, CLASS_NAME.getName(), "postInitialize",
                  UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE, "UIMAEE_initialized_controller__INFO",
                  new Object[] { getComponentName() });
          }
          super.serviceInitialized = true;
        }
      } 
    } catch (AsynchAEException e) {
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                "postInitialize", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_service_exception_WARNING", getComponentName());

        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                "postInitialize", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_exception__WARNING", e);
      }
      throw e;
    } catch (Exception e) {
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                "postInitialize", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_service_exception_WARNING", getComponentName());

        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                "postInitialize", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_exception__WARNING", e);
      }
      throw new AsynchAEException(e);
    }

  }

  /**
   * Forces initialization of a Cas Pool if this is a Cas Multiplier delegate collocated with an
   * aggregate. The parent aggregate calls this method when all type systems have been merged.
   */
  public synchronized void onInitialize() {
    // Component's Cas Pool is registered lazily, when the process() is called for
    // the first time. For monitoring purposes, we need the comoponent's Cas Pool
    // MBeans to register during initialization of the service. For a Cas Multiplier
    // force creation of the Cas Pool and registration of a Cas Pool with the JMX Server.
    // Just get the CAS and release it back to the component's Cas Pool.
    if (isCasMultiplier() && !isTopLevelComponent() ) {
    	boolean isUimaAggregate = false;
    	if ( !(resourceSpecifier instanceof CollectionReaderDescription) ) {
            //  determine if this AE is a UIMA aggregate
            isUimaAggregate = ((AnalysisEngineDescription) resourceSpecifier).isPrimitive() == false ? true : false;
        }
    	if ( !isUimaAggregate ) {  // !uima core aggregate CM
   	        CAS cas = (CAS) getUimaContext().getEmptyCas(CAS.class);
    	    cas.release();
    	}
    }
  }

  /**
	 * 
	 */
  public void collectionProcessComplete(Endpoint anEndpoint)// throws AsynchAEException
  {
    AnalysisEngine ae = null;
    long start = super.getCpuTime();
    localCache.dumpContents();
    try {
      ae = aeInstancePool.checkout();
      if (ae != null) {
        ae.collectionProcessComplete();
      }
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINEST)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINEST, getClass().getName(),
                "collectionProcessComplete", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_cpc_all_cases_processed__FINEST", new Object[] { getComponentName() });
      }
      getServicePerformance().incrementAnalysisTime(super.getCpuTime() - start);
      if (!anEndpoint.isRemote()) {
        UimaTransport transport = getTransport(anEndpoint.getEndpoint());
        UimaMessage message = transport.produceMessage(AsynchAEMessage.CollectionProcessComplete,
                AsynchAEMessage.Response, getName());
        // Send CPC completion reply back to the client. Use internal (non-jms) transport
        transport.getUimaMessageDispatcher(anEndpoint.getEndpoint()).dispatch(message);
      } else {
        getOutputChannel().sendReply(AsynchAEMessage.CollectionProcessComplete, anEndpoint, null, false);
      }

      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINE)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINE, getClass().getName(),
                "collectionProcessComplete", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_cpc_completed__FINE", new Object[] { getComponentName() });
      }

    } catch (Exception e) {
      ErrorContext errorContext = new ErrorContext();
      errorContext.add(AsynchAEMessage.Command, AsynchAEMessage.CollectionProcessComplete);
      errorContext.add(AsynchAEMessage.Endpoint, anEndpoint);
      getErrorHandlerChain().handle(e, errorContext, this);
    } finally {
      clearStats();
      if (ae != null) {
        try {
          aeInstancePool.checkin(ae);
        } catch (Exception ex) {
          if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
            UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                    "collectionProcessComplete", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                    "UIMAEE_service_exception_WARNING", getComponentName());

            UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                    "collectionProcessComplete", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                    "UIMAEE_unable_to_check_ae_back_to_pool__WARNING",
                    new Object[] { getComponentName(), ex });
          }
        }
      }
    }
  }
  /**
   * Conditionally creates and starts a timer thread that will time a task/method.
   * The timer is only created if the System property -DheapDumpThreshold=<x> is set.
   * The x is the max number of seconds the task/method is allowed before the timer pops.
   * 
   * @return instance of HeapDumpTimer or null
   */
  private StackDumpTimer ifEnabledStartHeapDumpTimer() {
	  try {
		  if ( System.getProperty(DUMP_HEAP_THRESHOLD) != null) {
			  int thresholdInSeconds = Integer.parseInt(System.getProperty(DUMP_HEAP_THRESHOLD));
			  //	Creates and returns an instance of a timer. The timer is auto started in a 
			  //	constructor and will expire in a given number of seconds
			  return new StackDumpTimer(thresholdInSeconds);
		  }
	  } catch( NumberFormatException e) {
		  UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
	                "ifEnabledStartHeapDumpTimer", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
	                "UIMAEE_exception__WARNING", e);
	  }
	  return null;
  }
  private AnalysisEngineManagement deepCopy(AnalysisEngineManagement aem) {
    String xml = xstream.toXML(aem); // serialize to XML
    return (AnalysisEngineManagement)xstream.fromXML(xml);
  }
  private void getLeafManagementObjects(AnalysisEngineManagement aem, List<AnalysisEngineManagement> result, boolean deepCopy) {
    if (aem.getComponents().isEmpty()) {
      if (!aem.getName().equals("Fixed Flow Controller")) {
        if ( deepCopy ) {
          result.add(deepCopy(aem)); // deep copy
        } else {
          result.add(aem); // reference
        }
      }
    } else {
      for (AnalysisEngineManagement child : (Iterable<AnalysisEngineManagement>) aem.getComponents().values()) {
        getLeafManagementObjects(child, result, deepCopy);
      }
    }
  }
  public void destroyAE()  {
	  try {
	    if ( aeInstancePool != null ) {
	      AnalysisEngine ae = aeInstancePool.checkout();
	      if ( ae != null ) {
	        ae.destroy();
	      }
	    }
	  } catch( AsynchAEException e) {
	    //  No-op. AE instance not found. Most likely, the UIMA AS service is in shutdown state
	  } catch( Exception e) {
		  e.printStackTrace();
	  }
  }
  /**
   * This is called when a Stop request is received from a client. Add the provided Cas id to the
   * list of aborted CASes. The process() method checks this list to determine if it should continue
   * generating children.
   * 
   * @param aCasReferenceId
   *          - Id of an input CAS. The client wants to stop generation of child CASes from this
   *          CAS.
   * 
   * @return
   */

  public void process(CAS aCAS, String aCasReferenceId, Endpoint anEndpoint) {

    if (stopped) {
      return;
    }
    List<AnalysisEngineManagement> beforeAnalysisManagementObjects = new ArrayList<AnalysisEngineManagement>();
    List<AnalysisEngineManagement> afterAnalysisManagementObjects = new ArrayList<AnalysisEngineManagement>();
    CasStateEntry parentCasStateEntry = null;
    //	If enabled, keep a reference to a timer which
    //  when it expires, will cause a JVM to dump a stack
    StackDumpTimer stackDumpTimer = null;
    try {
      parentCasStateEntry = getLocalCache().lookupEntry(aCasReferenceId);
    } catch (Exception e) {
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                "process", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_service_exception_WARNING", getComponentName());

        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                "process", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_exception__WARNING", e);
      }
      return;
    }

    long totalProcessTime = 0; // stored total time spent producing ALL CASes

    boolean inputCASReturned = false;
    boolean processingFailed = false;
    // This is a primitive controller. No more processing is to be done on the Cas. Mark the
    // destination as final and return CAS in reply.
    anEndpoint.setFinal(true);
    AnalysisEngine ae = null;
    boolean clientUnreachable = false;
    try {
      // Checkout an instance of AE from the pool
      ae = aeInstancePool.checkout();
      // Get input CAS entry from the InProcess cache
      long time = super.getCpuTime();
      //	Start the heap dump timer. This timer is only started if explicitly enabled
      //	via System property: -DheapDumpThreshold=<x> where is number of seconds the 
      //	method is allowed to complete. If the method is not complete in allowed window
      //	the heap and stack trace dump of all threads will be produced.
      stackDumpTimer = ifEnabledStartHeapDumpTimer();
      
      
      AnalysisEngineManagement rootAem = ae.getManagementInterface();
      if ( rootAem.getComponents().size() > 0 ) {
          getLeafManagementObjects(rootAem, beforeAnalysisManagementObjects, true);
      } else {
          beforeAnalysisManagementObjects.add(deepCopy(rootAem));   
      }
      
      CasIterator casIterator = ae.processAndOutputNewCASes(aCAS);
      if ( stackDumpTimer != null ) {
    	  stackDumpTimer.cancel();
    	  stackDumpTimer = null;   // nullify timer instance so that we dont have to worry about
          // it in case an exception happens below
      }
      
      // Store how long it took to call processAndOutputNewCASes()
      totalProcessTime = (super.getCpuTime() - time);
      long sequence = 1;
      long hasNextTime = 0; // stores time in hasNext()
      long getNextTime = 0; // stores time in next();
      boolean moreCASesToProcess = true;
      boolean casAbortedDueToExternalRequest = false;
      while (moreCASesToProcess) {
        long timeToProcessCAS = 0; // stores time in hasNext() and next() for each CAS
        hasNextTime = super.getCpuTime();
        //	Start the heap dump timer. This timer is only started if explicitly enabled
        //	via System property: -DheapDumpThreshold=<x> where x is a number of seconds the 
        //	method is allowed to complete. If the method is not complete in allowed window
        //	the heap and stack trace dump of all threads will be produced.
        stackDumpTimer = ifEnabledStartHeapDumpTimer();
        if (!casIterator.hasNext()) {
          moreCASesToProcess = false;
          // Measure how long it took to call hasNext()
          timeToProcessCAS = (super.getCpuTime() - hasNextTime);
          totalProcessTime += timeToProcessCAS;
          if ( stackDumpTimer != null ) {
        	  stackDumpTimer.cancel();
        	  stackDumpTimer = null;   // nullify timer instance so that we dont have to worry about
              // it in case an exception happens below
          }
          break; // from while
        }
        if ( stackDumpTimer != null ) {
        	stackDumpTimer.cancel();
        	stackDumpTimer = null;   // nullify timer instance so that we dont have to worry about
                                    // it in case an exception happens below
        }
        // Measure how long it took to call hasNext()
        timeToProcessCAS = (super.getCpuTime() - hasNextTime);
        getNextTime = super.getCpuTime();
        //	Start the heap dump timer. This timer is only started if explicitly enabled
        //	via System property: -DheapDumpThreshold=<x> where is number of seconds the 
        //	method is allowed to complete. If the method is not complete in allowed window
        //	the heap and stack trace dump of all threads will be produced.
        stackDumpTimer = ifEnabledStartHeapDumpTimer();
        CAS casProduced = casIterator.next();
        if ( stackDumpTimer != null ) {
        	stackDumpTimer.cancel();
        	stackDumpTimer = null;   // nullify timer instance so that we dont have to worry about
            // it in case an exception happens below
        }
        // Add how long it took to call next()
        timeToProcessCAS += (super.getCpuTime() - getNextTime);
        // Add time to call hasNext() and next() to the running total
        totalProcessTime += timeToProcessCAS;
        casAbortedDueToExternalRequest = abortGeneratingCASes(aCasReferenceId);
        // If the service is stopped or aborted, stop generating new CASes and just return the input
        // CAS
        if (stopped || casAbortedDueToExternalRequest) {
          if (getInProcessCache() != null && getInProcessCache().getSize() > 0
                  && getInProcessCache().entryExists(aCasReferenceId)) {
            try {
              // Set a flag on the input CAS to indicate that the processing was aborted
              getInProcessCache().getCacheEntryForCAS(aCasReferenceId).setAborted(true);
            } catch (Exception e) {
              // An exception be be thrown here if the service is being stopped.
              // The top level controller may have already cleaned up the cache
              // and the getCacheEntryForCAS() will throw an exception. Ignore it
              // here, we are shutting down.
            } finally {
              // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
              // We are terminating the iterator here, release the internal CAS lock
              // so that we can release the CAS. This approach may need to be changed
              // as there may potentially be a problem with a Class Loader.
              // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
              ((CASImpl) aCAS).enableReset(true);
              try {
                // We are either stopping the service or aborting input CAS due to explicit STOP
                // request
                // from a client. If a new CAS was produced, release it back to the pool.
                if (casProduced != null) {
                  casProduced.release();
                }
              } catch (Exception e) {
            	  if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.INFO)) {
                      UIMAFramework.getLogger(CLASS_NAME).logrb(
                              Level.INFO,
                              getClass().getName(),
                              "process",
                              UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                              "UIMAEE_cas_release_failed__INFO",
                              new Object[] { getComponentName(),
                                  aCasReferenceId });
                    }
              }
              if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.INFO)) {
                UIMAFramework.getLogger(CLASS_NAME).logrb(
                        Level.INFO,
                        getClass().getName(),
                        "process",
                        UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                        "UIMAEE_stopped_producing_new_cases__INFO",
                        new Object[] { Thread.currentThread().getId(), getComponentName(),
                            aCasReferenceId });
              }
            }
          }
          if (casAbortedDueToExternalRequest) {

            // The controller was told to stop generating new CASes. Just return the input CAS to
            // the
            // client
            // throw new ResourceProcessException(new
            // InterruptedException("Cas Multiplier:"+getComponentName()+" Aborted CAS:"+aCasReferenceId));

            break; // break out of the cas producing loop and return an input CAS to the client
          } else {
            // The controller is stopping
            return;
          }
        }
        OutOfTypeSystemData otsd = getInProcessCache().getOutOfTypeSystemData(aCasReferenceId);
        MessageContext mContext = getInProcessCache()
                .getMessageAccessorByReference(aCasReferenceId);
        CacheEntry newEntry = getInProcessCache().register(casProduced, mContext, otsd);
        // if this Cas Multiplier is not Top Level service, add new Cas Id to the private
        // cache of the parent aggregate controller. The Aggregate needs to know about
        // all CASes it has in play that were generated from the input CAS.
        CasStateEntry childCasStateEntry = null;
        if (!isTopLevelComponent()) {
          newEntry.setNewCas(true, parentController.getComponentName());
          // Create CAS state entry in the aggregate's local cache
          childCasStateEntry = parentController.getLocalCache().createCasStateEntry(
                  newEntry.getCasReferenceId());
          // Fetch the parent CAS state entry from the aggregate's local cache. We need to increment
          // number of child CASes associated with it.
          parentCasStateEntry = parentController.getLocalCache().lookupEntry(aCasReferenceId);
        } else {
          childCasStateEntry = getLocalCache().createCasStateEntry(newEntry.getCasReferenceId());
        }
        // Associate parent CAS (input CAS) with the new CAS.
        childCasStateEntry.setInputCasReferenceId(aCasReferenceId);
        // Increment number of child CASes generated from the input CAS
        parentCasStateEntry.incrementSubordinateCasInPlayCount();
        parentCasStateEntry.incrementOutstandingFlowCounter();

        // Associate input CAS with the new CAS
        newEntry.setInputCasReferenceId(aCasReferenceId);
        newEntry.setCasSequence(sequence);
        // Add to the cache how long it took to process the generated (subordinate) CAS
        getCasStatistics(newEntry.getCasReferenceId()).incrementAnalysisTime(timeToProcessCAS);
        if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINE)) {
          UIMAFramework.getLogger(CLASS_NAME).logrb(
                  Level.FINE,
                  getClass().getName(),
                  "process",
                  UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                  "UIMAEE_produced_new_cas__FINE",
                  new Object[] { Thread.currentThread().getName(),
                      getUimaContextAdmin().getQualifiedContextName(),
                      newEntry.getCasReferenceId(), aCasReferenceId });
        }
        // Add the generated CAS to the outstanding CAS Map. Client notification will release
        // this CAS back to its pool
        synchronized (syncObject) {
          if (isTopLevelComponent()) {
            // Add the id of the generated CAS to the map holding outstanding CASes. This
            // map will be referenced when a client sends Free CAS Notification. The map
            // stores the id of the CAS both as a key and a value. Map is used to facilitate
            // quick lookup
            cmOutstandingCASes.put(newEntry.getCasReferenceId(), newEntry.getCasReferenceId());
          }
          // Increment number of CASes processed by this service
          sequence++;
        }
        if (!anEndpoint.isRemote()) {
          UimaTransport transport = getTransport(anEndpoint.getEndpoint());
          UimaMessage message = transport.produceMessage(AsynchAEMessage.Process,
                  AsynchAEMessage.Request, getName());
          message.addStringProperty(AsynchAEMessage.CasReference, newEntry.getCasReferenceId());
          message.addStringProperty(AsynchAEMessage.InputCasReference, aCasReferenceId);
          message.addLongProperty(AsynchAEMessage.CasSequence, sequence);
          ServicePerformance casStats = getCasStatistics(aCasReferenceId);

          message.addLongProperty(AsynchAEMessage.TimeToSerializeCAS, casStats
                  .getRawCasSerializationTime());
          message.addLongProperty(AsynchAEMessage.TimeToDeserializeCAS, casStats
                  .getRawCasDeserializationTime());
          message.addLongProperty(AsynchAEMessage.TimeInProcessCAS, casStats.getRawAnalysisTime());
          long iT = getIdleTimeBetweenProcessCalls(AsynchAEMessage.Process);
          message.addLongProperty(AsynchAEMessage.IdleTime, iT);
          if (!stopped) {
            transport.getUimaMessageDispatcher(anEndpoint.getEndpoint()).dispatch(message);
            dropStats(newEntry.getCasReferenceId(), getName());
          }
        } else {
          // Send generated CAS to the remote client
          if (!stopped) {
              getOutputChannel().sendReply(newEntry, anEndpoint);
            
              //	Check for delivery failure. The client may have terminated while an input CAS was being processed
            if ( childCasStateEntry.deliveryToClientFailed() ) {
              clientUnreachable = true;
              if ( cmOutstandingCASes.containsKey(childCasStateEntry.getCasReferenceId())) {
              	  cmOutstandingCASes.remove(childCasStateEntry.getCasReferenceId());
          	  }
              //	Stop generating new CASes. We failed to send a CAS to a client. Most likely
              //	the client has terminated. 
          	  moreCASesToProcess = false; // exit the while loop
          	  
          	  dropCAS(childCasStateEntry.getCasReferenceId(), true);
            }
          }
        }
        // Remove new CAS state entry from the local cache if this is a top level primitive.
        // If not top level, the client (an Aggregate) will remove this entry when this new
        // generated CAS reaches Final State.
        if (isTopLevelComponent()) {
          try {
            localCache.lookupEntry(newEntry.getCasReferenceId()).setDropped(true);
          } catch (Exception e) {
          }
          localCache.remove(newEntry.getCasReferenceId());
        }

        // Remove Stats from the global Map associated with the new CAS
        // These stats for this CAS were added to the response message
        // and are no longer needed
        dropCasStatistics(newEntry.getCasReferenceId());
      } // while

      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINEST)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(
                Level.FINEST,
                getClass().getName(),
                "process",
                UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_completed_analysis__FINEST",
                new Object[] { Thread.currentThread().getName(), getComponentName(),
                    aCasReferenceId, (double) (super.getCpuTime() - time) / (double) 1000000 });
      }
      getMonitor().resetCountingStatistic("", Monitor.ProcessErrorCount);
      // Set total number of children generated from this CAS
      // Store total time spent processing this input CAS
      getCasStatistics(aCasReferenceId).incrementAnalysisTime(totalProcessTime);

      //  Fetch AE's management information that includes per component performance stats
      //  These stats are internally maintained in a Map. If the AE is an aggregate
      //  the Map will contain AnalysisEngineManagement instance for each AE.
      AnalysisEngineManagement aem = ae.getManagementInterface();
      if ( aem.getComponents().size() > 0) {
          //  Flatten the hierarchy by recursively (if this AE is an aggregate) extracting  
          //  primitive AE's AnalysisEngineManagement instance and placing it in 
          //  afterAnalysisManagementObjects List.
          getLeafManagementObjects(aem, afterAnalysisManagementObjects, false);
      } else {
          //  Add the top level AnalysisEngineManagement instance.
          afterAnalysisManagementObjects.add(aem);    
      }

      //  Create a List to hold per CAS analysisTime and total number of CASes processed
      //  by each AE. This list will be serialized and sent to the client
      List<AnalysisEnginePerformanceMetrics> performanceList = 
        new ArrayList<AnalysisEnginePerformanceMetrics>();
      //  Diff the before process() performance metrics with post process performance
      //  metrics
      for (AnalysisEngineManagement after : afterAnalysisManagementObjects) {
        for( AnalysisEngineManagement before: beforeAnalysisManagementObjects) {
          if ( before.getUniqueMBeanName().equals(after.getUniqueMBeanName())) {
            AnalysisEnginePerformanceMetrics metrics = 
              new AnalysisEnginePerformanceMetrics(after.getName(),
                      after.getUniqueMBeanName(),
                      after.getAnalysisTime()- before.getAnalysisTime(),
                      after.getNumberOfCASesProcessed());
            performanceList.add(metrics);
            break;
          }
        }
      }
      //  Save this component performance metrics
      parentCasStateEntry.getAEPerformanceList().addAll(performanceList);
      
      if (!anEndpoint.isRemote()) {
        inputCASReturned = true;
        UimaTransport transport = getTransport(anEndpoint.getEndpoint());

        if (getInProcessCache() != null && getInProcessCache().getSize() > 0
                && getInProcessCache().entryExists(aCasReferenceId)) {
          try {
            CacheEntry ancestor = 
                      getInProcessCache().
                        getTopAncestorCasEntry(getInProcessCache().getCacheEntryForCAS(aCasReferenceId));
            if ( ancestor != null ) {
                // Set a flag on the input CAS to indicate that the processing was aborted
               ancestor.addDelegateMetrics(getKey(), performanceList);
            }
          } catch (Exception e) {
            // An exception be be thrown here if the service is being stopped.
            // The top level controller may have already cleaned up the cache
            // and the getCacheEntryForCAS() will throw an exception. Ignore it
            // here, we are shutting down.
          }
        }          
        
        UimaMessage message = transport.produceMessage(AsynchAEMessage.Process,
                AsynchAEMessage.Response, getName());
        message.addStringProperty(AsynchAEMessage.CasReference, aCasReferenceId);
        ServicePerformance casStats = getCasStatistics(aCasReferenceId);

        message.addLongProperty(AsynchAEMessage.TimeToSerializeCAS, casStats
                .getRawCasSerializationTime());
        message.addLongProperty(AsynchAEMessage.TimeToDeserializeCAS, casStats
                .getRawCasDeserializationTime());
        message.addLongProperty(AsynchAEMessage.TimeInProcessCAS, casStats.getRawAnalysisTime());
        long iT = getIdleTimeBetweenProcessCalls(AsynchAEMessage.Process);
        message.addLongProperty(AsynchAEMessage.IdleTime, iT);
        // Send reply back to the client. Use internal (non-jms) transport
        if (!stopped) {
          transport.getUimaMessageDispatcher(anEndpoint.getEndpoint()).dispatch(message);
          dropStats(aCasReferenceId, getName());
        }
      } else {
        try {
          
          CacheEntry entry =
                  getInProcessCache().getCacheEntryForCAS(aCasReferenceId);
          entry.addDelegateMetrics(getKey(), performanceList);
        } catch (Exception e) {
          // An exception be be thrown here if the service is being stopped.
          // The top level controller may have already cleaned up the cache
          // and the getCacheEntryForCAS() will throw an exception. Ignore it
          // here, we are shutting down.
        }

        if (!stopped && !clientUnreachable ) {
            getOutputChannel().sendReply(getInProcessCache().getCacheEntryForCAS(aCasReferenceId), anEndpoint);
        }

        inputCASReturned = true;
      }
      
      // Remove input CAS state entry from the local cache
      if (!isTopLevelComponent()) {
        localCache.lookupEntry(aCasReferenceId).setDropped(true);
        localCache.remove(aCasReferenceId);
      }
    } catch (Throwable e) {
      
      if ( e instanceof OutOfMemoryError ) {
        e.printStackTrace();
        System.err.println("\n\n\n\t!!!!! UIMA AS Service Caught Java Error While in process() method. Exiting via System.exit(2)\n\n\n");
        System.err.flush();
        System.exit(2);
      }
    	if ( stackDumpTimer != null ) {
    		stackDumpTimer.cancel();
    	}
      processingFailed = true;
      ErrorContext errorContext = new ErrorContext();
      errorContext.add(AsynchAEMessage.CasReference, aCasReferenceId);
      errorContext.add(AsynchAEMessage.Command, AsynchAEMessage.Process);
      errorContext.add(AsynchAEMessage.MessageType, AsynchAEMessage.Request);
      errorContext.add(AsynchAEMessage.Endpoint, anEndpoint);
      // Handle the exception. Pass reference to the PrimitiveController instance
      getErrorHandlerChain().handle(e, errorContext, this);
    } finally {
      dropCasStatistics(aCasReferenceId);

      if (ae != null) {
        try {
          aeInstancePool.checkin(ae);
        } catch (Exception e) {
          if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
            UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                    "process", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                    "UIMAEE_service_exception_WARNING", getComponentName());

            UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                    "process", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                    "UIMAEE_exception__WARNING", e);
          }
        }
      }
      // drop the CAS if it has been successfully processed. If there was a failure, the Error
      // Handler
      // will drop the CAS
      if (isTopLevelComponent() && !processingFailed) {
        // Release CASes produced from the input CAS if the input CAS has been aborted
        if (abortGeneratingCASes(aCasReferenceId) || clientUnreachable ) {

          if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.INFO)) {
            UIMAFramework.getLogger(CLASS_NAME).logrb(Level.INFO, CLASS_NAME.getName(), "process",
                    UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE, "UIMAEE_remove_cache_entry__INFO",
                    new Object[] { getComponentName(), aCasReferenceId });
          }
          getInProcessCache().releaseCASesProducedFromInputCAS(aCasReferenceId);
        } else if (inputCASReturned && isTopLevelComponent()) {
        	
        	if ( clientUnreachable ) {
        		((CASImpl) aCAS).enableReset(true);
        	}
          // Remove input CAS cache entry if the CAS has been sent to the client
          dropCAS(aCasReferenceId, true);
          localCache.dumpContents();
        }
      }
    }
  }

  private void addConfigIntParameter(String aParamName, int aParamValue) {
    ConfigurationParameter cp = new ConfigurationParameter_impl();
    cp.setMandatory(false);
    cp.setMultiValued(false);
    cp.setName(aParamName);
    cp.setType("Integer");
    getAnalysisEngineMetadata().getConfigurationParameterDeclarations().addConfigurationParameter(
            cp);
    getAnalysisEngineMetadata().getConfigurationParameterSettings().setParameterValue(aParamName,
            aParamValue);

  }

  // Return metadata
  public void sendMetadata(Endpoint anEndpoint) throws AsynchAEException {
    addConfigIntParameter(AnalysisEngineController.AEInstanceCount, analysisEnginePoolSize);

    if (getAnalysisEngineMetadata().getOperationalProperties().getOutputsNewCASes()) {
      addConfigIntParameter(AnalysisEngineController.CasPoolSize, super.componentCasPoolSize);
    }
    super.sendMetadata(anEndpoint, getAnalysisEngineMetadata());
  }

  private AnalysisEngineMetaData getAnalysisEngineMetadata() {
    return analysisEngineMetadata;
  }

  /**
   * Executes action on error. Primitive Controller allows two types of actions TERMINATE and
   * DROPCAS.
   */
  public void takeAction(String anAction, String anEndpointName, ErrorContext anErrorContext) {
    try {
      if (ErrorHandler.TERMINATE.equalsIgnoreCase(anAction)
              || ErrorHandler.DROPCAS.equalsIgnoreCase(anAction)) {
        super.handleAction(anAction, anEndpointName, anErrorContext);
      }
    } catch (Exception e) {
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                "takeAction", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_service_exception_WARNING", getComponentName());

        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                "takeAction", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_exception__WARNING", e);
      }
    }
  }

  public String getServiceEndpointName() {
    return getName();
  }

  public synchronized ControllerLatch getControllerLatch() {
    return latch;
  }

  public boolean isPrimitive() {
    return true;
  }

  public Monitor getMonitor() {
    return super.monitor;
  }

  public void setMonitor(Monitor monitor) {
    this.monitor = monitor;
  }

  public void handleDelegateLifeCycleEvent(String anEndpoint, int aDelegateCount) {
    if (aDelegateCount == 0) {
      // tbi
    }
  }

  protected String getNameFromMetadata() {
    return super.getMetaData().getName();
  }

  public void setAnalysisEngineInstancePool(AnalysisEngineInstancePool aPool) {
    aeInstancePool = aPool;
  }

  public PrimitiveServiceInfo getServiceInfo() {
    if (serviceInfo == null) {
      serviceInfo = new PrimitiveServiceInfo(isCasMultiplier(), this);
      serviceInfo.setServiceKey(delegateKey);
    }
    if (isTopLevelComponent() && getInputChannel() != null) {
      serviceInfo.setInputQueueName(getInputChannel().getServiceInfo().getInputQueueName());
      serviceInfo.setBrokerURL(super.getBrokerURL());
      serviceInfo.setDeploymentDescriptorPath(super.aeDescriptor);
    }
    serviceInfo.setAnalysisEngineInstanceCount(analysisEnginePoolSize);
    return serviceInfo;
  }

  public void stop() {
    super.stop(true);  // shutdown now
    if (aeInstancePool != null) {
      try {
        aeInstancePool.destroy();
      } catch (Exception e) {
        if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
          UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                  "stop", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                  "UIMAEE_service_exception_WARNING", getComponentName());

          UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                  "stop", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                  "UIMAEE_exception__WARNING", e);
        }
      }
    }
    try {
      for (Entry<String, UimaTransport> transport : transports.entrySet()) {
        transport.getValue().stopIt();
      }
    } catch (Exception e) {
      if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.WARNING)) {
        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, CLASS_NAME.getName(),
                "stop", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_service_exception_WARNING", getComponentName());

        UIMAFramework.getLogger(CLASS_NAME).logrb(Level.WARNING, getClass().getName(),
                "stop", UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE,
                "UIMAEE_exception__WARNING", e);
      }
    }

    if (cmOutstandingCASes != null) {
      if (!cmOutstandingCASes.isEmpty()) {
        // If there are outstanding CASes, force them to be released
        // If the CM is blocking on getCAS() this will unblock it and
        // enable termination. Otherwise, a hang may occur
        Iterator<String> it = cmOutstandingCASes.keySet().iterator();
        while (it.hasNext()) {
          String casId = it.next();
          try {
            CacheEntry entry = getInProcessCache().getCacheEntryForCAS(casId);
            if (entry != null && entry.getCas() != null) {
        	  if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.INFO)) {
                  UIMAFramework.getLogger(CLASS_NAME).logrb(Level.INFO, CLASS_NAME.getName(), "stop",
                          UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE, "UIMAEE_force_cas_release___INFO",
                          new Object[] { getComponentName(), casId });
              }
              // Force CAS release to unblock CM thread
              entry.getCas().release();
            }
          } catch (Exception e) {
        	  if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.INFO)) {
                  UIMAFramework.getLogger(CLASS_NAME).logrb(Level.INFO, CLASS_NAME.getName(), "stop",
                          UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE, "UIMAEE_parent_cas_notin_cache__INFO",
                          new Object[] { getComponentName(), casId });
              }
          }
        }

      }
      cmOutstandingCASes.clear();
    }
    if (aeList != null) {
      aeList.clear();
      aeList = null;
    }
    if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.INFO)) {
          UIMAFramework.getLogger(CLASS_NAME).logrb(Level.INFO, CLASS_NAME.getName(), "stop",
                  UIMAEE_Constants.JMS_LOG_RESOURCE_BUNDLE, "UIMAEE_service_stopped__INFO",
                  new Object[] { getComponentName()});
    }
  }
  /**
   This method forces a heap and java dump. It only works with IBM jvm. 
   **/
  protected final synchronized void forceStackDump() {
	  Class<?> params[] = {};
      Object paramsObj[] = {};
      
	  
	  try {
		  Class<?> dumpClass = Class.forName("com.ibm.jvm.Dump");
	      //	Get a handle to the static method
	      Method javaDumpMethod = dumpClass.getDeclaredMethod("JavaDump", params);
	      //	dump the stack of all threads
	      javaDumpMethod.invoke(null, paramsObj);
	  } catch( ClassNotFoundException e) {
		  //	ignore ClassNotFoundException in case of sun jvm
	  } catch( InvocationTargetException e) {
		  
	  } catch (NoSuchMethodException e) {
		  
	  } catch( IllegalAccessException e) {
		  
	  }
  }
  
  /**
   * The HeapDumpTimer is optionally used to dump the heap if a task takes too much time to finish.
   * It is enabled from the System property -DheapDumpThreshold=<x> where x is a number of seconds 
   * the task is allowed to complete. If the task is not completed, the heap dump will be created. 
   * 
   *
   */
  public class StackDumpTimer  {
	  Timer timer;
	  
	  public StackDumpTimer ( int seconds )   {
	    timer = new Timer (  ) ;
	    timer.schedule ( new dumpTheStackTask (  ) , seconds*1000 ) ;
	  }
	  public void cancel() {
		  timer.cancel();
		  timer.purge();
	  }
	  class dumpTheStackTask extends TimerTask  {
	    public void run (  )   {
	    	timer.cancel (  ) ; //Terminate the thread
	    	// create a heap dump. NOTE: this only works with IBM jvm
	    	synchronized (threadDumpMonitor) {
	            long now = System.currentTimeMillis();
	            if (30000 < (now-lastDump)) {
	              // create a stack dump. NOTE: this only works with IBM jvm
	              forceStackDump();
	              lastDump = now;
	            }
          }
	    }
	  }

  }

  public void dumpState(StringBuffer buffer, String lbl1) {
    buffer.append(getComponentName()+" State:"+getState());
  }
}
