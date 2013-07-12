/**
 * Pleiades
 * Copyright (C) 2011 - 2012
 * Computational Intelligence Research Group (CIRG@UP)
 * Department of Computer Science
 * University of Pretoria
 * South Africa
 */
package net.pleiades;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.IMap;
import com.hazelcast.core.IQueue;
import com.hazelcast.core.Message;
import com.hazelcast.core.MessageListener;
import com.hazelcast.core.Transaction;
import java.io.File;
import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import net.pleiades.cluster.HazelcastCommunicator;
import net.pleiades.results.CilibSampleGatherer;
import net.pleiades.results.SampleGatherer;
import net.pleiades.simulations.Simulation;
import net.pleiades.tasks.Task;


public class ResultsListener implements EntryListener, MessageListener<Task> {
    private SampleGatherer gatherer;
    private Properties properties;

    public ResultsListener() {
        this.gatherer = new CilibSampleGatherer();
        this.properties = Config.getConfiguration();

        addListeners();
    }

    private void addListeners() {
        Config.RESULTS_TOPIC.addMessageListener(this);
    }

    public void execute() {
        HazelcastCommunicator cluster = new HazelcastCommunicator();
        cluster.connect();
        System.out.println("Now connected to Pleiades Cluster.\nWaiting for results...");

        Hazelcast.<String, Simulation>getMap(Config.completedMap).addEntryListener(this, true);
    }

    @Override
    public void entryAdded(EntryEvent event) {
        System.out.println("Entry added key=" + event.getKey() + ", value=" + event.getValue());
    }

    @Override
    public void entryRemoved(EntryEvent event) {
        System.out.println("Entry removed key=" + event.getKey() + ", value=" + event.getValue() + "\n");
    }

    @Override
    public void entryUpdated(EntryEvent event) {

    }

    @Override
    public void entryEvicted(EntryEvent event) {
        System.out.println("Entry evicted key=" + event.getKey() + ", value=" + event.getValue());
    }

    @Override
    public void onMessage(Message<Task> message) {
        Task t = message.getMessageObject();

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
        
        System.out.println("Task completed:" + message.getMessageObject().getId());

        Lock cLock = Hazelcast.getLock(Config.completedMap);
        cLock.lock();
        IMap<String, Simulation> completedMap = Hazelcast.getMap(Config.completedMap);

        txn.begin();

        try {
            Simulation cSimulation = completedMap.get(t.getParent().getID());

            if (cSimulation == null) {
                Config.RESULTS_TOPIC.publish(t); //republish this completed task
                System.out.println("ERROR: No such simulation!!");
                throw new Exception("No such simulation");
            }
            cSimulation.completeTask(t, properties);

            if (cSimulation.isComplete()) {
                System.out.println("\nGathering: " + cSimulation.getID());
                System.out.println(cSimulation.getResults().size() + " tasks completed.");

                gatherer.gatherResults(completedMap, cSimulation);

                if (cSimulation.jobComplete()) {
                    Utils.emailUser(cSimulation, new File(properties.getProperty("email_complete_template")), properties, "");

                    IQueue<String> errors = Hazelcast.getQueue(Config.errorQueue);
                    IMap<String, byte[]> fileQueue = Hazelcast.getMap(Config.fileMap);

                    Iterator<String> iter = errors.iterator();

                    while (iter.hasNext()) {
                        if (iter.next().startsWith(cSimulation.getJobID())) {
                            iter.remove();
                        }
                    }
                    System.out.println("Error Queue Size: " + errors.size());

                    fileQueue.remove(cSimulation.getFileKey());
                    fileQueue.forceUnlock(cSimulation.getFileKey());
                    System.out.println("File Queue Size: " + fileQueue.size());
                }
            } else {
                completedMap.put(t.getParent().getID(), cSimulation);
                completedMap.forceUnlock(t.getParent().getID());
            }
            
            txn.commit();
        } catch (Throwable e) {
            //System.out.println("ERROR:");
            //e.printStackTrace();
            txn.rollback();
        } finally {
            cLock.unlock();
        }
    }
}
