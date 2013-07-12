/**
 * Pleiades
 * Copyright (C) 2011 - 2012
 * Computational Intelligence Research Group (CIRG@UP)
 * Department of Computer Science
 * University of Pretoria
 * South Africa
 */
package net.pleiades;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.IMap;
import com.hazelcast.core.IQueue;
import com.hazelcast.core.Message;
import com.hazelcast.core.MessageListener;
import com.hazelcast.core.Transaction;
import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import net.pleiades.cluster.HazelcastCommunicator;
import net.pleiades.simulations.Simulation;
import net.pleiades.tasks.Task;

public class ErrorListener implements MessageListener<Task> {
    private Properties properties;

    public ErrorListener() {
        this.properties = Config.getConfiguration();

        addListeners();
    }

    private void addListeners() {
        Config.ERRORS_TOPIC.addMessageListener(this);
    }

    public void exexcute() {
        HazelcastCommunicator cluster = new HazelcastCommunicator();
        cluster.connect();
    }

    @Override
    public void onMessage(Message<Task> message) {
        Task t = message.getMessageObject();
        System.out.println("Error received:" + t.getId());
        
        Lock rLock = Hazelcast.getLock(Config.runningMap);
        rLock.lock();

        IMap<String, String> runningMap = Hazelcast.getMap(Config.runningMap);

        Transaction txn = Hazelcast.getTransaction();
        txn.begin();

        try {
            runningMap.remove(t.getId());
            txn.commit();
        } catch (Throwable e) {
            txn.rollback();
        } finally {
            runningMap.forceUnlock(t.getId());
            rLock.unlock();
        }
        
        Lock cLock = Hazelcast.getLock(Config.completedMap);
        Lock jLock = Hazelcast.getLock(Config.simulationsMap);
        cLock.lock();
        jLock.lock();

        IMap<String, Simulation> simulationsMap = Hazelcast.getMap(Config.simulationsMap);
        IMap<String, Simulation> completedMap = Hazelcast.getMap(Config.completedMap);

        txn.begin();

        String simulationKey = t.getParent().getID();

        try {
            String id;
            if (t.getOutput().equals("CANCELLED")) {
                id = t.getParent().getJobID();
                System.out.println("*** " + id);
            } else {
                id = simulationKey;
            }

            //remember erroneous simulations
            IQueue<String> errors = Hazelcast.getQueue(Config.errorQueue);
            for (String s : errors) {
                if (s.equals(id)) {
                    throw new Exception("Error already handeled.");
                }
            }
            errors.add(id);

            //remove simulation from jobs- and results queues
            //Simulation job = simulationsMap.get(simulationKey);
//            int simNum = t.getParent().getSimulationNumber();

            Iterator<Simulation> iter = simulationsMap.values().iterator();
//            System.out.println("JOBS: " + jobs.size());
            
            Simulation current;
            while (iter.hasNext()) {
                current = iter.next();
//                System.out.println("Current: " + current.getID());
                if ((current).getID().startsWith(id)) {
//                    System.out.println("rm " + current.getID());
                    completedMap.remove(current.getID());
                    completedMap.forceUnlock(current.getID());
                    iter.remove();
                }
            }

            txn.commit();

            //email user
            if (!t.getOutput().equals("CANCELLED")) {
                Utils.emailUser(t.getParent(), new File(properties.getProperty("email_error_template")), properties, t.getOutput());
            } else {
                System.out.println("[Cancelled by user]");
            }
        } catch (Throwable e) {
            txn.rollback();
        } finally {
            simulationsMap.forceUnlock(simulationKey);
            jLock.unlock();
            cLock.unlock();
        }
    }
}
