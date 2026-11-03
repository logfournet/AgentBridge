package me.sfclog.agentbridge.common;

import com.google.gson.annotations.SerializedName;

/**
 * Directory entry returned in LIST_RES.
 */
public class Entry {
    @SerializedName("name")
    public String name;
    @SerializedName("dir")
    public boolean dir;
    @SerializedName("size")
    public long size;
    @SerializedName("modified")
    public long modified;  // epoch ms

    public Entry() {}

    public Entry(String name, boolean dir, long size, long modified) {
        this.name = name;
        this.dir = dir;
        this.size = size;
        this.modified = modified;
    }
}