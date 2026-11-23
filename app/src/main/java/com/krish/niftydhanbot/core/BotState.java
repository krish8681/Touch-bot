package com.krish.niftydhanbot.core;

/** Operating states from the project record. Only TRADING allows new entries. */
public enum BotState {
    OFF,        // service stopped
    STARTING,   // connecting, loading data
    READY,      // connected and warmed up, waiting for the user to start trading
    TRADING,    // allowed to open new trades
    PAUSED,     // no new entries; exits still managed
    RECOVERY,   // found something unexpected at Dhan (e.g. an open F&O position); entries blocked
    ERROR       // something broke; entries blocked, exits still managed
}
