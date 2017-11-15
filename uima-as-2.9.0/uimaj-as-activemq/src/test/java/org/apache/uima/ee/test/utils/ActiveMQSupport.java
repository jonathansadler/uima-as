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

package org.apache.uima.ee.test.utils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.BindException;
import java.net.URI;
import java.util.concurrent.Semaphore;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;

import junit.framework.TestCase;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.TransportConnector;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.broker.region.policy.SharedDeadLetterStrategy;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.store.memory.MemoryPersistenceAdapter;
import org.apache.activemq.usage.MemoryUsage;
import org.apache.activemq.usage.SystemUsage;
import org.apache.camel.Exchange;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.uima.UIMAFramework;
import org.apache.uima.adapter.jms.JmsConstants;
import org.apache.uima.jms.error.handler.BrokerConnectionException;
import org.apache.uima.util.Level;
import org.junit.After;
import org.junit.Before;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.util.ErrorHandler;

public class ActiveMQSupport extends TestCase {
  private static final Class CLASS_NAME = ActiveMQSupport.class;
  protected static final String DEFAULT_BROKER_URL_KEY="DefaultBrokerURL";
  protected static final String DEFAULT_BROKER_URL_KEY_2="DefaultBrokerURL2";
  protected static final String DEFAULT_HTTP_BROKER_URL_KEY="DefaultHttpBrokerURL";
  protected static final String DEFAULT_HTTP_BROKER_URL_KEY_2="DefaultHttpBrokerURL2";
  protected static final int DEFAULT_BROKER_PORT=61617;
  protected static final int DEFAULT_BROKER_PORT_2=61620;
  protected static final int DEFAULT_HTTP_PORT = 18888;
  protected static final int DEFAULT_HTTP_PORT2 = 18890;

  
  protected static BrokerService broker;

  /**
   * Is set to uri of started broker for the TCP connection
   */
  protected String uri = null;

  protected static ThreadGroup brokerThreadGroup = null;

  protected static TransportConnector tcpConnector = null;

  protected static final String relativePath = "src" + System.getProperty("file.separator")
          + "test" + System.getProperty("file.separator") + "resources"
          + System.getProperty("file.separator") + "deployment";

  protected static final String resourceDirPath = "src" + System.getProperty("file.separator")
          + "test" + System.getProperty("file.separator") + "resources";
 
  protected static final String relativeDataPath = "src" + System.getProperty("file.separator")
          + "test" + System.getProperty("file.separator") + "resources"
          + System.getProperty("file.separator") + "data";

  protected static TransportConnector httpConnector = null;

  public static Semaphore brokerSemaphore = new Semaphore(1);
  @Before
  public synchronized void setUp() throws Exception {
    super.setUp();
	  //BasicConfigurator.configure();
    /*
    ConsoleAppender console = new ConsoleAppender(); //create appender
    //configure the appender
    String PATTERN = "%d [%p|%c|%C{1}] %m%n";
    console.setLayout(new PatternLayout(PATTERN)); 
    console.setThreshold(org.apache.log4j.Level.WARN);
    console.activateOptions();
    //add appender to any Logger (here is root)
    Logger.getRootLogger().addAppender(console);
    */
    broker = createBroker();  // sets uri
    /*
    broker.setUseJmx(false);
    if ( broker.isUseJmx()) {
        broker.getManagementContext().setConnectorPort(1098);
    }
    */
    SystemUsage su = new SystemUsage();
    MemoryUsage mu = new MemoryUsage();
    mu.setPercentOfJvmHeap(50);
    su.setMemoryUsage(mu);
    broker.setSystemUsage(su);
    broker.start();
    //System.out.println("Broker Version:"+broker.getBroker().)
    //    broker.setMasterConnectorURI(uri);
    addHttpConnector(DEFAULT_HTTP_PORT);
    if ( System.getProperty(DEFAULT_BROKER_URL_KEY) != null) {
      System.clearProperty(DEFAULT_BROKER_URL_KEY);
    }
    System.setProperty(DEFAULT_BROKER_URL_KEY, broker.getDefaultSocketURIString());
    if ( System.getProperty(DEFAULT_HTTP_BROKER_URL_KEY) != null) {
      System.clearProperty(DEFAULT_HTTP_BROKER_URL_KEY);
    }
    System.setProperty(DEFAULT_HTTP_BROKER_URL_KEY, httpConnector.getConnectUri().toString());
    // define property so that UIMA AS error handler doesnt call System.exit() if the
    // error handler action=terminate.
    System.setProperty("dontKill","");
    System.setProperty("EXTENDED_TESTS","TRUE");
  }
  

  public void drainQueues() throws Exception {
	  if ( broker != null ) {
		  if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINEST)) {
			  String msg = "............ Deleting ALL messages";
	          UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINEST, CLASS_NAME.getName(), "drainQueues",
	                 JmsConstants.JMS_LOG_RESOURCE_BUNDLE, "UIMAJMS_debug_msg__FINEST",
	                  new Object[] { msg });
	      }
		  broker.deleteAllMessages();
	  }
  }
  protected void cleanBroker( BrokerService targetBroker) throws Exception {
    // Remove messages from all queues
   // targetBroker.deleteAllMessages();
    org.apache.activemq.broker.Connection[] connections = targetBroker.getRegionBroker().getClients();
    for( org.apache.activemq.broker.Connection connection : connections) {
      try {
		  if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINEST)) {
	    	  String msg = ".............. Forcing Connection Closure - Connection ID:"+connection.getConnectionId();
	          UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINEST, CLASS_NAME.getName(), "cleanBroker",
	                 JmsConstants.JMS_LOG_RESOURCE_BUNDLE, "UIMAJMS_debug_msg__FINEST",
	                  new Object[] { msg });
	      }
        connection.stop();
      } catch( Exception e) {
        e.printStackTrace();
      }
    }

    ActiveMQDestination[] destinations = targetBroker.getRegionBroker().getDestinations();

    if ( destinations != null ) {
      for( ActiveMQDestination destination: destinations ) {
        if ( !destination.isTopic() ) {
          targetBroker.removeDestination(destination);
        }
      }
    }
  }
  
  protected String addHttpConnector(int aDefaultPort) throws Exception {
    return addHttpConnector(broker, aDefaultPort);
  }
  
  protected String addHttpConnector(BrokerService aBroker, int aDefaultPort) throws Exception {
    boolean found = false;
    while( !found ) {
      try {
        httpConnector = addConnector(aBroker, "http",aDefaultPort);
        //  Use reflection to determine if the AMQ version is at least 5.2. If it is, we must
        //  plug in a broker to the httpConnector otherwise we get NPE when starting the connector.
        //  AMQ version 4.1.1 doesn't exhibit this problem.
        try {
          Method m = httpConnector.getClass().getDeclaredMethod("setBrokerService", new Class[] {BrokerService.class});
          m.invoke(httpConnector, aBroker);
        } catch ( NoSuchMethodException e) {
          //  Ignore, this is not AMQ 5.2
        }
        Logger.getRootLogger().info("Adding HTTP Connector:" + httpConnector.getConnectUri()+" Name:"+httpConnector.getName());
        httpConnector.start();
        return httpConnector.getUri().toString();
      } catch ( BindException e) { 
        aDefaultPort++;
      } catch ( IOException e) {
        if ( e.getCause() != null && e.getCause() instanceof BindException ) {
          aDefaultPort++;
        } else {
          throw new BrokerConnectionException("Unexpected Exception While Connecting to Broker with URL:"+uri+"\n"+e);
        }
      } 
    }
    throw new BrokerConnectionException("Unable to acquire Open Port for HTTPConnector");
  }
  
  protected String getHttpURI() throws Exception {
    while ( httpConnector == null  ) {
     synchronized(this) {
       this.wait(100); 
     }
    }
    return httpConnector.getConnectUri().toString();
  }
  
  protected void removeQueue(String aQueueName) throws Exception {
    httpConnector.stop();
  }

  protected void removeHttpConnector() throws Exception {
    httpConnector.stop();
    broker.removeConnector(httpConnector);
  }
  
  protected TransportConnector addConnector(BrokerService aBroker, String type, int basePort) 
  throws BrokerConnectionException{
    boolean found = false;
    TransportConnector transportConnector = null;
    while( !found ) {
      try {
        String uri = type+"://localhost:" + basePort;
        transportConnector = aBroker.addConnector(uri);
        found = true;
      } catch ( BindException e) { 
        basePort++;
      } catch ( IOException e) {
        if ( e.getCause() != null && e.getCause() instanceof BindException ) {
          basePort++;
        } else {
        	e.printStackTrace();
          throw new BrokerConnectionException("Unexpected Exception While Connecting to Broker with URL:"+uri+"\n"+e);
        }
      } catch( Exception e) {
        throw new BrokerConnectionException("Unexpected Exception While Connecting to Broker with URL:"+uri+"\n"+e);
      }
    }
    return transportConnector;
  }

  protected String getBrokerUri() {
//	  return "failover:("+uri+")";
    return uri;
  }

  protected ConnectionFactory createConnectionFactory() throws Exception {
    return new ActiveMQConnectionFactory(uri);
  }

  protected Connection getConnection() throws Exception {
    return createConnectionFactory().createConnection();
  }

  public BrokerService createBroker() throws Exception {
    return createBroker(DEFAULT_BROKER_PORT, false);
  }

  protected BrokerService createBroker(int port,boolean secondaryBroker) throws Exception {
      String hostName = "localhost"; 
      BrokerService broker = 
        BrokerFactory.createBroker(new URI("broker:()/" + hostName + "?persistent=false"));
      tcpConnector = addConnector(broker, "tcp",port);
      uri = tcpConnector.getUri().toString();
      Logger.getRootLogger().info(">>>> Starting Broker With URL:" + uri);
      int defaultJMXPort = 1098;
      if ( secondaryBroker ) {
    	  defaultJMXPort = 1097;
        broker.getManagementContext().setJmxDomainName(broker.getManagementContext().getJmxDomainName()+".test");      
        tcpConnector.setName(DEFAULT_BROKER_URL_KEY_2);
      } else {
        tcpConnector.setName(DEFAULT_BROKER_URL_KEY);
      }
  	  boolean enableJMX = true;
  	  String jmxFlag = System.getProperty("uima.as.enable.jmx");
  	  if ( jmxFlag != null && jmxFlag.equalsIgnoreCase("false") ) {
  		enableJMX = false;
  	  }

      if ( enableJMX ) {
          broker.setUseJmx(enableJMX);
    	  broker.getManagementContext().setConnectorPort(defaultJMXPort);
      } else {
    	  System.out.println("************** ACTIVEMQ JMX Connector Not Enabled ****************");
      }
      PolicyEntry policy = new PolicyEntry();
      policy.setDeadLetterStrategy(new SharedDeadLetterStrategy());

      PolicyMap pMap = new PolicyMap();
      pMap.setDefaultEntry(policy);

      broker.setDestinationPolicy(pMap);
      broker.setPersistenceAdapter(new MemoryPersistenceAdapter());
      broker.setPersistent(false);
      broker.setUseShutdownHook(true);
      broker.setUseLoggingForShutdownErrors(false);
      try {
          Method method = broker.getClass().getDeclaredMethod("setSchedulerSupport", new Class[] {Boolean.TYPE});
          method.invoke(broker, new Object[] {Boolean.FALSE});
      } catch( NoSuchMethodException e) {
    	  //	ignore
      }

      return broker;
  }
  protected BrokerService setupSecondaryBroker(boolean addProperty) throws Exception {
    System.setProperty("activemq.broker.jmx.domain","org.apache.activemq.test");

    BrokerService broker2 = createBroker(DEFAULT_BROKER_PORT_2, true);
    broker2.start();
    if ( addProperty ) {
      System.setProperty("BrokerURL", broker2.getConnectorByName(DEFAULT_BROKER_URL_KEY_2).getUri().toString());
    }
    return broker2;
  }
  protected void stopBroker() throws Exception {
    if (broker != null) {
	  if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINEST)) {
	      String msg = ">>> Stopping Broker";
          UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINEST, CLASS_NAME.getName(), "stopBroker",
                 JmsConstants.JMS_LOG_RESOURCE_BUNDLE, "UIMAJMS_debug_msg__FINEST",
                  new Object[] { msg });
      }
      if (tcpConnector != null) {
        tcpConnector.stop();
        broker.removeConnector(tcpConnector);
        //Logger.getRootLogger().info(message);
        Logger.getRootLogger().info("Broker Connector:" + tcpConnector.getUri().toString() + " is stopped");
      }
      
      removeHttpConnector();
      broker.deleteAllMessages();

    //  cleanBroker(broker);
      broker.stop();
      broker.waitUntilStopped();
//      cleanBroker(broker);
	  if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINEST)) {
	      String msg = ">>> Broker Stopped";
          UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINEST, CLASS_NAME.getName(), "stopBroker",
                 JmsConstants.JMS_LOG_RESOURCE_BUNDLE, "UIMAJMS_debug_msg__FINEST",
                  new Object[] { msg });
      }
    }
  }
  @After
  public synchronized void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty("activemq.broker.jmx.domain");
    System.clearProperty("BrokerURL");
  
    wait(3000);
    if ( !broker.isStopped()) {
        cleanBroker(broker);
        stopBroker();
    }
  }
  
  public class UimaASErrorHandler implements ErrorHandler {

	@Override
	public void handleError(Throwable arg0) {
	}
	  
  }
  
  public class TestDefaultMessageListenerContainer extends DefaultMessageListenerContainer 
  implements ExceptionListener {
	  volatile boolean  failed = false;
	  String reason = "";
	  public TestDefaultMessageListenerContainer() {
		  super();
		  setExceptionListener(this);
	  }
	  protected void recoverAfterListenerSetupFailure() {
	  }
	  protected void handleListenerSetupFailure(Throwable t, boolean alreadyHandled) {
		  if ( !failed && !broker.isStopped()) {
			  try {

				  stopSharedConnection();
				  stop();
				  if (UIMAFramework.getLogger(CLASS_NAME).isLoggable(Level.FINEST)) {
					  String msg = "JMS Listener.shutdown() called";
			          UIMAFramework.getLogger(CLASS_NAME).logrb(Level.FINEST, CLASS_NAME.getName(), "handleListenerSetupFailure",
			                 JmsConstants.JMS_LOG_RESOURCE_BUNDLE, "UIMAJMS_debug_msg__FINEST",
			                  new Object[] { msg });
			      }
				  //stop();
				  failed = true;
				  reason = t.getMessage();
			  } catch( Exception e) {
				  
			  }
		  }
	  }
	  public boolean failed() { 
		  return failed;
	  }
	  public String getReasonForFailure() {
		  return reason;
	  }
@Override
protected void handleListenerException(Throwable ex) {
}
@Override
public void afterPropertiesSet() {
	// TODO Auto-generated method stub
	super.afterPropertiesSet();
}
@Override
public void onException(JMSException exception) {
	// TODO Auto-generated method stub
}
  }

  
  public class UimaASExceptionHandler implements ExceptionListener {

	@Override
	public void onException(JMSException arg0) {
	}
	  
  }
}
