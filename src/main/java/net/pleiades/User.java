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
import com.hazelcast.core.Transaction;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import net.pleiades.simulations.Simulation;
import net.pleiades.simulations.creator.SimulationsCreator;
import net.pleiades.simulations.creator.XMLSimulationsCreator;

public class User {
    public static void uploadJob(String input, String jar, String user, String userEmail, String releaseType) {
        File run = null;
        byte[] runBytes = null;

        // In case of debugging emergency copy/paste the following line!!!
        //System.out.println("1");

        try {
            System.out.println(">Using jar file: " + jar);
            run = new File(jar);

            FileInputStream runInputStream = new FileInputStream(run);
            runBytes = new byte[runInputStream.available()];
            runInputStream.read(runBytes);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (run == null) {
            System.out.println(">Error: jar file not valid!");
            System.exit(1);
        }

        Lock jLock = Hazelcast.getLock(Config.simulationsMap);
        Lock fLock = Hazelcast.getLock(Config.fileMap);
        Lock cLock = Hazelcast.getLock(Config.completedMap);

        jLock.lock();
        fLock.lock();
        cLock.lock();

        File inputFile = new File(input);
        String absPath = inputFile.getAbsolutePath();
        String jobName = absPath.substring(absPath.replaceAll("\\\\", "/").lastIndexOf("/") + 1, absPath.lastIndexOf("."));

        System.out.println(">Creating job from file: " + input);
        System.out.println(">Job name set to: " + jobName);
        SimulationsCreator creator = new XMLSimulationsCreator(user, userEmail, user, jobName, releaseType);
        List<Simulation> simulations = creator.createSimulations(inputFile, "");//runBytes);
        String fileKey = simulations.get(0).getFileKey();

        System.out.println(">Your job contains " + simulations.size() + " simulations.");

        IMap<String, Simulation> simulationsMap = Hazelcast.getMap(Config.simulationsMap);
        IMap<String, byte[]> fileQueue = Hazelcast.getMap(Config.fileMap);
        IMap<String, Simulation> completedMap = Hazelcast.getMap(Config.completedMap);

        Transaction txn = Hazelcast.getTransaction();
        txn.begin();

        int tasks = 0;
        try {

            System.out.println(">Uploading tasks to cluster...");
            fileQueue.put(fileKey, runBytes);
            
            for (Simulation s : simulations) {
                tasks += s.getSamples();
                
                simulationsMap.put(s.getID(), s);
                completedMap.put(s.getID(), s.emptyClone());
                
                simulationsMap.forceUnlock(s.getID());
                completedMap.forceUnlock(s.getID());
            }
            
            txn.commit();

            System.out.println("100% >");
        } catch (Throwable e) {
            e.printStackTrace();
            txn.rollback();
        } finally {
            simulationsMap.forceUnlock(user);
            fileQueue.forceUnlock(fileKey);
            cLock.unlock();
            fLock.unlock();
            jLock.unlock();
        }

        System.out.println(tasks + " tasks uploaded.");
        System.out.println(">Your job was created successfully.\n>You will be notified via email (" + userEmail + ") when your results are available.>");

        Hazelcast.getLifecycleService().shutdown();
        System.exit(0);
    }
}
