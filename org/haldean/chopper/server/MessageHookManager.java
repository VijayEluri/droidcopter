package org.haldean.chopper.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class MessageHookManager extends Thread {
    private static MessageHookManager instance = null;
    public static synchronized MessageHookManager getInstance() {
	if (instance == null) {
	    instance = new MessageHookManager();
	    instance.defaultHooks();
	}
	return instance;
    }

    List<MessageHook> hooks;
    Map<String, List<MessageHook>> prefixes;
    Queue<String> queue;

    private MessageHookManager() {
	setName("Message hook manager");

	hooks = new ArrayList<MessageHook>();
	prefixes = new HashMap<String, List<MessageHook>>();
	queue = new LinkedBlockingQueue<String>();

	start();
    }

    public void defaultHooks() {
	addHook(new PidLogger());
    }

    public static void addHook(MessageHook hook) {
	getInstance().addHookInternal(hook);
    }

    private void addHookInternal(MessageHook hook) {
	hooks.add(hook);

	String[] hookPrefixes = hook.processablePrefixes();
	for (String prefix : hookPrefixes) {
	    if (prefixes.containsKey(prefix)) {
		prefixes.get(prefix).add(hook);
	    } else {
		ArrayList<MessageHook> prefixHooks = new ArrayList<MessageHook>();
		prefixHooks.add(hook);
		prefixes.put(prefix, prefixHooks);
	    }
	}
    }

    private void processMessage(String message) {
	Message m = new Message(message);
	
	for (String prefix : prefixes.keySet()) {
	    if (m.prefixMatches(prefix)) {
		for (MessageHook hook : prefixes.get(prefix)) {
		    hook.process(m);
		}
	    }
	}
    }

    public static void queue(String message) {
	getInstance().queue.add(message);
    }

    public void run() {
	while (true) {
	    String message = queue.poll();
	    if (message != null) {
		processMessage(message);
	    } else {
		try {
		    Thread.sleep(50);
		} catch (InterruptedException e) {
		    Debug.log("MessageHookManager's sleep was interrupted.");
		}
	    }
	}
    }
}
		