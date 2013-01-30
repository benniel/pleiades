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
import com.hazelcast.core.ITopic;
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

    public ErrorListener(Properties properties) {
        this.properties = properties;

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
        System.out.println("Error received:" + message.getMessageObject().getId());
        Lock cLock = Hazelcast.getLock(Config.completedMap);
        Lock jLock = Hazelcast.getLock(Config.simulationsMap);
        cLock.lock();
        jLock.lock();

        IMap<String, List<Simulation>> simulationsMap = Hazelcast.getMap(Config.simulationsMap);
        IMap<String, Simulation> completedMap = Hazelcast.getMap(Config.completedMap);

        Transaction txn = Hazelcast.getTransaction();
        txn.begin();

        Task t = message.getMessageObject();

        String simulationsKey = t.getParent().getOwner();

        try {
            String simulationID = t.getParent().getID();

            //remember erroneous simulations
            IQueue<String> errors = Hazelcast.getQueue(Config.errorQueue);
            for (String s : errors) {
                if (s.equals(simulationID)) {
                    throw new Exception("Error already handeled.");
                }
            }
            errors.add(simulationID);

            //remove simulation from jobs- and results queues
            List<Simulation> jobs = simulationsMap.get(simulationsKey);
            int simNum = t.getParent().getSimulationNumber();

            Iterator<Simulation> iter = jobs.iterator();

            Simulation current;
            while (iter.hasNext()) {
                if ((current = iter.next()).getID().equals(simulationID)
                        && current.getSimulationNumber() == simNum) {

                    completedMap.remove(current.getID());
                    completedMap.forceUnlock(current.getID());
                    iter.remove();
                }
            }

            txn.commit();

            //email user
            Utils.emailUser(t.getParent(), new File(properties.getProperty("email_error_template")), properties, t.getOutput());

        } catch (Throwable e) {
            txn.rollback();
        } finally {
            simulationsMap.forceUnlock(simulationsKey);
            jLock.unlock();
            cLock.unlock();
        }
    }
}