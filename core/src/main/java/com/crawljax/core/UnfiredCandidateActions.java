package com.crawljax.core;

import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.Lock;

import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crawljax.core.configuration.BrowserConfiguration;
import com.crawljax.core.state.Eventable;
import com.crawljax.core.state.StateFlowGraph;
import com.crawljax.core.state.StateVertex;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.Striped;

/**
 * Contains all the {@link CandidateCrawlAction}s that still have to be fired to get a result.
 */
@Singleton
public class UnfiredCandidateActions {

	private static final Logger LOG = LoggerFactory.getLogger(UnfiredCandidateActions.class);

	private final Map<Integer, Queue<CandidateCrawlAction>> cache;
	private final BlockingQueue<Integer> statesWithCandidates;
	private final Striped<Lock> locks;
	private final Provider<StateFlowGraph> sfg;

	UnfiredCandidateActions(BrowserConfiguration config, Provider<StateFlowGraph> sfg) {
		this.sfg = sfg;
		cache = Maps.newHashMap();
		statesWithCandidates = Queues.newLinkedBlockingQueue();
		// Every browser gets a lock.
		locks = Striped.lock(config.getNumberOfBrowsers());
	}

	/**
	 * @param state
	 *            The state you want to poll an {@link CandidateCrawlAction} for.
	 * @return The next to-be-crawled action or <code>null</code> if none available.
	 */
	CandidateCrawlAction pollActionOrNull(StateVertex state) {
		Lock lock = locks.get(state.getId());
		try {
			lock.lock();
			Queue<CandidateCrawlAction> queue = cache.get(state.getId());
			if (queue == null) {
				return null;
			} else {
				CandidateCrawlAction action = queue.poll();
				if (action == null) {
					LOG.debug("All actions polled for state {}", state.getName());
					cache.remove(state.getId());
					LOG.debug("There are now {} states with unfinished actions", cache.size());
				}
				removeStateFromQueue(state.getId());
				return action;
			}
		} finally {
			lock.unlock();
		}
	}

	private void removeStateFromQueue(int id) {
		while (statesWithCandidates.remove(id)) {
			LOG.trace("Removed id {} from the queue", id);
		}
	}

	/**
	 * @param actions
	 *            The actions you want to add to a state.
	 * @param state
	 *            The state name. This should be unique per state.
	 */
	void addActions(Collection<CandidateCrawlAction> actions, StateVertex state) {
		Lock lock = locks.get(state.getId());
		try {
			lock.lock();
			LOG.debug("Adding crawl actions for state {}", state.getId());
			if (cache.containsKey(state.getId())) {
				cache.get(state.getId()).addAll(actions);
			} else {
				cache.put(state.getId(), Queues.newConcurrentLinkedQueue(actions));
			}
			statesWithCandidates.add(state.getId());
		} finally {
			lock.unlock();
		}

	}

	/**
	 * @return If there are any pending actions to be crawled. This method is not threadsafe and
	 *         might return a stale value.
	 */
	public boolean isEmpty() {
		return statesWithCandidates.isEmpty();
	}

	/**
	 * @return A new crawl task as soon as one is ready. Until then, it blocks.
	 * @throws InterruptedException
	 *             when taking from the queue is interrupted.
	 */
	public StateVertex awaitNewTask() throws InterruptedException {
		int id = statesWithCandidates.take();
		// Put it back the end of the queue. It will be removed later.
		statesWithCandidates.add(id);
		LOG.debug("New task polled for state {}", id);
		LOG.info("There are {} states with unfired actions", statesWithCandidates.size());
		return sfg.get().getById(id);
	}
}
