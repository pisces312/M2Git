package ts.realms.m2git.core.network.mws.fs;

import java.util.LinkedHashMap;
import java.util.Map;

import io.milton.cache.CacheManager;

public class LocalCacheManager implements CacheManager {

    private int maximumWeightedCapacity = 1000;

    public LocalCacheManager() {
    }


    @Override
    public Map getMap(String name) {
        final int cap = maximumWeightedCapacity;
        return new LinkedHashMap(cap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > cap;
            }
        };
    }

    public int getMaximumWeightedCapacity() {
        return maximumWeightedCapacity;
    }

    public void setMaximumWeightedCapacity(int maximumWeightedCapacity) {
        this.maximumWeightedCapacity = maximumWeightedCapacity;
    }
}
