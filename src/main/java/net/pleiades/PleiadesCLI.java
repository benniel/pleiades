/**
 * Pleiades
 * Copyright (C) 2011 - 2012
 * Computational Intelligence Research Group (CIRG@UP)
 * Department of Computer Science
 * University of Pretoria
 * South Africa
 */
package net.pleiades;

import com.google.common.base.Preconditions;
import com.hazelcast.core.Hazelcast;
import java.util.Properties;
import net.pleiades.cluster.HazelcastCommunicator;
import net.pleiades.database.UserDBCommunicator;
import net.pleiades.persistence.CompletedMapPersistence;
import org.apache.commons.cli.*;

/**
 *
 * @author bennie
 */
public class PleiadesCLI {
    static final String VERSION = "0.2";
    static final int MIN_MEMBERS = 2;

    public static void main(String args[]) {
        CommandLineParser parser = new GnuParser();

        try {
            Options options = Config.createOptions();
            CommandLine cli = parser.parse(options, args);
            handleCommandline(options, cli, args);
        } catch (ParseException e) {
            System.err.println("Error: Failed to parse command line arguments. Use --help to see options.\n" + e.getMessage());
            System.exit(1);
        }
    }

    private static void user(Properties properties, CommandLine cli, String user) {
        HazelcastCommunicator cluster = new HazelcastCommunicator();
        //UserDBCommunicator database = Utils.authenticate(properties, user);
        UserDBCommunicator database = Utils.connectToDatabase(properties);

        cluster.connect();
        int clusterSize = Hazelcast.getCluster().getMembers().size();

        if (clusterSize < MIN_MEMBERS) {
            System.out.println("Error: Too few cluster members active.>Connection terminated. If problem persists, contact cluster administrator.");
            System.exit(0);
        }

        System.out.println("Now connected to Pleiades Cluster (" + clusterSize +" members).>You are logged in as " + user + ".>");

        if (cli.hasOption("input")) {
            String input = cli.getOptionValue("input");
            String jar = null;
            if (cli.hasOption("jar")) {
                jar = cli.getOptionValue("jar");
            }

            boolean cont = cli.hasOption("continue");

            User.uploadJob(properties, input, jar, user, database.getUserEmail(user));
        } else {
            //User.showDetails(user);
        }
    }

    private static void continueJob(CommandLine cli) {
        Config.CONTINUE_TOPIC.publish(cli.getOptionValue("continue"));
    }

    private static void handleCommandline(Options options, CommandLine cli, String args[]) {
        Preconditions.checkState(args.length > 0, "Error: You must either specify a user or start Pleiades member in worker mode! Use --help for more options.");

        Properties properties;

        if (cli.hasOption("help")) {
            new HelpFormatter().printHelp("Pleiades", options);
            System.exit(0);
        }

        if (cli.hasOption("config")) {
            properties = Config.getConfiguration(cli.getOptionValue("config"));
        } else {
            properties = Config.getConfiguration("pleiades.conf");
        }

        CompletedMapPersistence.setProperties(properties); //bit of a hack :-/

        if (cli.hasOption("worker")) {
            //Preconditions.checkState(cli.getOptions().length == 1, "Option --worker must be used without any other options.");
            new WorkerPool(properties, Integer.parseInt(cli.getOptionValue("worker", "1")), cli.hasOption("quiet")).execute();
        } else if (cli.hasOption("register")) {
            Preconditions.checkState(cli.getOptions().length == 1, "Option --register must be used without any other options.");
            Utils.connectToDatabase(properties).registerNewUser();
            System.out.println("Thank you for registering on Pleiades! You may now log in with your new username.");
        } else if (cli.hasOption("gatherer")) {
            Preconditions.checkState(cli.getOptions().length < 3, "Too many options");
            if (cli.getOptions().length == 2) {
                Preconditions.checkState(cli.hasOption("continue"), "Gatherer can only be used with option --continue");
            }
            new Gatherer(properties).start(cli.hasOption("continue"));
        } else if (cli.hasOption("monitor")) {
            Preconditions.checkState(cli.getOptions().length == 1, "Option --monitor must be used without any other options.");
            System.out.println("Monitor is no longer supported in cli mode."); System.exit(0);
        } else if (cli.hasOption("user")) {
            String user = cli.getOptionValue("user");
            user(properties, cli, user);
        } else {
            System.out.println("Error: You must specify a user or start Pleiades in worker mode! Use --help for more options.");
        }
    }
}