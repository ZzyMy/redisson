/**
 * Copyright (c) 2013-2019 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.tomcat;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import javax.servlet.http.HttpSession;

import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Session;
import org.apache.catalina.SessionEvent;
import org.apache.catalina.SessionListener;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.redisson.Redisson;
import org.redisson.api.RSet;
import org.redisson.api.RMap;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.redisson.config.Config;

/**
 * Redisson Session Manager for Apache Tomcat
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonSessionManager extends ManagerBase implements Lifecycle {

    public enum ReadMode {REDIS, MEMORY}
    public enum UpdateMode {DEFAULT, AFTER_REQUEST}
    
    private final Log log = LogFactory.getLog(RedissonSessionManager.class);
    
    protected LifecycleSupport lifecycle = new LifecycleSupport(this);
    
    private RedissonClient redisson;
    private String configPath;
    private ReadMode readMode = ReadMode.REDIS;
    private UpdateMode updateMode = UpdateMode.DEFAULT;
    private String keyPrefix = "";
    private boolean broadcastSessionEvents = false;

    private final String nodeId = UUID.randomUUID().toString();

    private UpdateValve updateValve;

    private Codec codecToUse;

    public String getNodeId() { return nodeId; }

    public String getUpdateMode() {
        return updateMode.toString();
    }

    public void setUpdateMode(String updateMode) {
        this.updateMode = UpdateMode.valueOf(updateMode);
    }
    
    public boolean isBroadcastSessionEvents() {
        return broadcastSessionEvents;
    }
    
    public void setBroadcastSessionEvents(boolean replicateSessionEvents) {
        this.broadcastSessionEvents = replicateSessionEvents;
    }

    public String getReadMode() {
        return readMode.toString();
    }

    public void setReadMode(String readMode) {
        this.readMode = ReadMode.valueOf(readMode);
    }
    
    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }
    
    public String getConfigPath() {
        return configPath;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    @Override
    public int getRejectedSessions() {
        return 0;
    }
    
    @Override
    public void load() throws ClassNotFoundException, IOException {
    }

    @Override
    public void setRejectedSessions(int sessions) {
    }

    @Override
    public void unload() throws IOException {
    }

    @Override
    public void addLifecycleListener(LifecycleListener listener) {
        lifecycle.addLifecycleListener(listener);
    }

    @Override
    public LifecycleListener[] findLifecycleListeners() {
        return lifecycle.findLifecycleListeners();
    }

    @Override
    public void removeLifecycleListener(LifecycleListener listener) {
        lifecycle.removeLifecycleListener(listener);
    }

    @Override
    public Session createSession(String sessionId) {
        Session session = super.createSession(sessionId);
        
        if (broadcastSessionEvents) {
            getTopic().publish(new SessionCreatedMessage(getNodeId(), session.getId()));
            session.addSessionListener(new SessionListener() {
                @Override
                public void sessionEvent(SessionEvent event) {
                    if (event.getType() == Session.SESSION_DESTROYED_EVENT) {
                        getTopic().publish(new SessionDestroyedMessage(getNodeId(), session.getId()));
                    }
                }
            });
        }
        return session;
    }

    public RSet<String> getNotifiedNodes(String sessionId) {
        String separator = keyPrefix == null || keyPrefix.isEmpty() ? "" : ":";
        String name = keyPrefix + separator + "redisson:tomcat_notified_nodes:" + sessionId;
        return redisson.getSet(name, StringCodec.INSTANCE);
    }

    public RMap<String, Object> getMap(String sessionId) {
        String separator = keyPrefix == null || keyPrefix.isEmpty() ? "" : ":";
        String name = keyPrefix + separator + "redisson:tomcat_session:" + sessionId;
        return redisson.getMap(name, new CompositeCodec(StringCodec.INSTANCE, codecToUse, codecToUse));
    }

    public RTopic getTopic() {
        String separator = keyPrefix == null || keyPrefix.isEmpty() ? "" : ":";
        final String name = keyPrefix + separator + "redisson:tomcat_session_updates:" + ((Context) getContainer()).getName();
        return redisson.getTopic(name);
    }
    
    @Override
    public Session findSession(String id) throws IOException {
        return findSession(id, true);
    }
    
    private Session findSession(String id, boolean notify) throws IOException {
        Session result = super.findSession(id);
        if (result == null) {
            if (id != null) {
                Map<String, Object> attrs = new HashMap<String, Object>();
                try {
                    attrs = getMap(id).getAll(RedissonSession.ATTRS);
                } catch (Exception e) {
                    log.error("Can't read session object by id: " + id, e);
                }

                if (attrs.isEmpty() || (broadcastSessionEvents && getNotifiedNodes(id).contains(nodeId))) {  
                    log.info("Session " + id + " can't be found");
                    return null;	
                }
                
                RedissonSession session = (RedissonSession) createEmptySession();
                session.load(attrs);
                session.setId(id, notify);
                
                session.access();
                session.endAccess();
                return session;
            }
            return null;
        }

        result.access();
        result.endAccess();
        
        return result;
    }
    
    @Override
    public Session createEmptySession() {
        return new RedissonSession(this, readMode, updateMode, broadcastSessionEvents);
    }
    
    @Override
    public void add(Session session) {
        super.add(session);
        ((RedissonSession)session).save();
    }
    
    @Override
    public void remove(Session session) {
        super.remove(session);
        
        if (session.getIdInternal() != null) {
            ((RedissonSession)session).delete();
        }
    }
    
    public RedissonClient getRedisson() {
        return redisson;
    }
    
    @Override
    public void start() throws LifecycleException {
        redisson = buildClient();
        
        final ClassLoader applicationClassLoader;
        if (getContainer().getLoader().getClassLoader() != null) {
            applicationClassLoader = getContainer().getLoader().getClassLoader();
        } else if (Thread.currentThread().getContextClassLoader() != null) {
            applicationClassLoader = Thread.currentThread().getContextClassLoader();
        } else {
            applicationClassLoader = getClass().getClassLoader();
        }
        
        Codec codec = redisson.getConfig().getCodec();
        try {
            codecToUse = codec.getClass()
                    .getConstructor(ClassLoader.class, codec.getClass())
                    .newInstance(applicationClassLoader, codec);
        } catch (Exception e) {
            throw new LifecycleException(e);
        }
        
        if (updateMode == UpdateMode.AFTER_REQUEST) {
            Pipeline pipeline = getEngine().getPipeline();
            if (updateValve != null) { // in case startInternal is called without stopInternal cleaning the updateValve
                pipeline.removeValve(updateValve);
            }
            updateValve = new UpdateValve(this);
            pipeline.addValve(updateValve);			
        }
        
        if (readMode == ReadMode.MEMORY || broadcastSessionEvents) {
            RTopic updatesTopic = getTopic();
            updatesTopic.addListener(AttributeMessage.class, new MessageListener<AttributeMessage>() {
                
                @Override
                public void onMessage(CharSequence channel, AttributeMessage msg) {
                    try {
                        if (msg.getNodeId().equals(nodeId)) {
                            return;
                        }

                        RedissonSession session = (RedissonSession) RedissonSessionManager.super.findSession(msg.getSessionId());
                        if (session != null) {
                            if (msg instanceof SessionDestroyedMessage) {
                                session.expire();
                            }
                            
                            if (msg instanceof AttributeRemoveMessage) {
                                for (String name : ((AttributeRemoveMessage)msg).getNames()) {
                                    session.superRemoveAttributeInternal(name, true);
                                }
                            }

                            if (msg instanceof AttributesClearMessage) {
                                RedissonSessionManager.super.remove(session);
                            }
                            
                            if (msg instanceof AttributesPutAllMessage) {
                                AttributesPutAllMessage m = (AttributesPutAllMessage) msg;
                                Map<String, Object> attrs = m.getAttrs(codecToUse.getMapValueDecoder());
                                session.load(attrs);
                            }
                            
                            if (msg instanceof AttributeUpdateMessage) {
                                AttributeUpdateMessage m = (AttributeUpdateMessage)msg;
                                session.superSetAttribute(m.getName(), m.getValue(codecToUse.getMapValueDecoder()), true);
                            }
                        } else {
                            if (msg instanceof SessionCreatedMessage) {
                                Session s = findSession(msg.getSessionId());
                                if (s == null) {
                                    throw new IllegalStateException("Unable to find session: " + msg.getSessionId());
                                }
                            }
                            
                            if (msg instanceof SessionDestroyedMessage) {
                                Session s = findSession(msg.getSessionId(), false);
                                if (s == null) {
                                    throw new IllegalStateException("Unable to find session: " + msg.getSessionId());
                                }
                                s.expire();
                                RSet<String> set = getNotifiedNodes(msg.getSessionId());
                                set.add(nodeId);
                            }
                        }

                    } catch (Exception e) {
                        log.error("Unable to handle topic message", e);
                    }
                }
            });
        }
        
        lifecycle.fireLifecycleEvent(START_EVENT, null);
    }

    protected RedissonClient buildClient() throws LifecycleException {
        Config config = null;
        try {
            config = Config.fromJSON(new File(configPath), getClass().getClassLoader());
        } catch (IOException e) {
            // trying next format
            try {
                config = Config.fromYAML(new File(configPath), getClass().getClassLoader());
            } catch (IOException e1) {
                log.error("Can't parse json config " + configPath, e);
                throw new LifecycleException("Can't parse yaml config " + configPath, e1);
            }
        }
        
        try {
            return Redisson.create(config);
        } catch (Exception e) {
            throw new LifecycleException(e);
        }
    }

    @Override
    public void stop() throws LifecycleException {
        if (updateValve != null) {
            getEngine().getPipeline().removeValve(updateValve);
            updateValve = null;
        }

        codecToUse = null;

        try {
            shutdownRedisson();
        } catch (Exception e) {
            throw new LifecycleException(e);
        }
        
        lifecycle.fireLifecycleEvent(STOP_EVENT, null);
    }

    protected void shutdownRedisson() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    public void store(HttpSession session) throws IOException {
        if (session == null) {
            return;
        }
        
        RedissonSession sess = (RedissonSession) super.findSession(session.getId());
        if (sess != null) {
            sess.access();
            sess.endAccess();
            sess.save();
        }
    }
    
}
