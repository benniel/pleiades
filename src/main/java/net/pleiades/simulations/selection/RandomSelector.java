/**
 * Pleiades
 * Copyright (C) 2011 - 2012
 * Computational Intelligence Research Group (CIRG@UP)
 * Department of Computer Science
 * University of Pretoria
 * South Africa
 */
package net.pleiades.simulations.selection;

import java.util.Random;
import net.pleiades.database.SimulationsMapStore;

/**
 *
 * @author bennie
 */
public class RandomSelector implements SimulationSelector {

    @Override
    public String getKey(SimulationsMapStore simulationsDB) {
        Random random = new Random();

        Object[] keySet = simulationsDB.loadAllKeys().toArray();
        int keys = keySet.length;
        double rand = random.nextDouble();

        int selected = (int)Math.ceil(rand * keys) - 1;
        
        if (selected < 0) {
            return "";
        }
        
        return (String)keySet[selected];
    }
}
