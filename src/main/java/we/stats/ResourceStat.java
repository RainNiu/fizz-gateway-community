package we.stats;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 
 * @author Francis Dong
 *
 */
public class ResourceStat {

	/**
	 * Resource ID
	 */
	private String resourceId;

	/**
	 * Request count of time slot, the beginning timestamp(timeId) as key
	 */
	private ConcurrentMap<Long, TimeSlot> timeSlots = new ConcurrentHashMap<>(100);

	/**
	 * Concurrent requests
	 */
	private AtomicLong concurrentRequests = new AtomicLong(0);

	private ReentrantReadWriteLock rwl1 = new ReentrantReadWriteLock();
	private ReentrantReadWriteLock rwl2 = new ReentrantReadWriteLock();
	private Lock w1 = rwl1.writeLock();
	private Lock w2 = rwl2.writeLock();

	public ResourceStat(String resourceId) {
		this.resourceId = resourceId;
	}

	/**
	 * Returns Time slot of the specified time slot ID
	 * 
	 * @param timeSlotId
	 * @return
	 */
	public TimeSlot getTimeSlot(long timeSlotId) {
		if (timeSlots.containsKey(timeSlotId)) {
			return timeSlots.get(timeSlotId);
		} else {
			TimeSlot timeSlot = new TimeSlot(timeSlotId);
			TimeSlot old = timeSlots.putIfAbsent(timeSlotId, timeSlot);
			if (old != null) {
				return old;
			} else {
				return timeSlot;
			}
		}
	}

	/**
	 * Increase concurrent request counter of the resource
	 * 
	 * @param timeSlotId
	 * @param maxCon
	 * @return false if exceed the maximum concurrent request of the specified
	 *         resource
	 */
	public boolean incrConcurrentRequest(long timeSlotId, Long maxCon) {
		w1.lock();
		try {
			boolean isExceeded = false;
			if (maxCon != null && maxCon.intValue() > 0) {
				long n = this.concurrentRequests.get();
				if (n >= maxCon.longValue()) {
					isExceeded = true;
					this.incrBlockRequestToTimeSlot(timeSlotId);
				} else {
					long conns = this.concurrentRequests.incrementAndGet();
					this.getTimeSlot(timeSlotId).updatePeakConcurrentReqeusts(conns);
				}
			} else {
				long conns = this.concurrentRequests.incrementAndGet();
				this.getTimeSlot(timeSlotId).updatePeakConcurrentReqeusts(conns);
			}
			return !isExceeded;
		} finally {
			w1.unlock();
		}
	}

	/**
	 * Decrease concurrent request counter of the resource
	 * 
	 */
	public void decrConcurrentRequest(long timeSlotId) {
		long conns = this.concurrentRequests.decrementAndGet();
		this.getTimeSlot(timeSlotId).updatePeakConcurrentReqeusts(conns);
	}

	/**
	 * Increase block request to the specified time slot
	 * 
	 */
	private void incrBlockRequestToTimeSlot(long timeSlotId) {
		this.getTimeSlot(timeSlotId).getBlockRequests().incrementAndGet();
	}

	/**
	 * Add request to the specified time slot
	 * 
	 * @param timeSlotId
	 * @return false if exceed the maximum RPS of the specified resource
	 */
	public boolean incrRequestToTimeSlot(long timeSlotId, Long maxRPS) {
		w2.lock();
		try {
			boolean isExceeded = false;
			if (maxRPS != null && maxRPS.intValue() > 0) {
//				TimeWindowStat timeWindowStat = this.getCurrentTimeWindowStat(resourceId, curTimeSlotId);
//				if (new BigDecimal(maxRPS).compareTo(timeWindowStat.getRps()) <= 0) {
//					isExceeded = true;
//					resourceStat.incrBlockRequestToTimeSlot(curTimeSlotId);
//				}

				// time slot unit is one second
				long total = this.getTimeSlot(timeSlotId).getCounter().get();
				long max = Long.valueOf(maxRPS);
				if (total >= max) {
					isExceeded = true;
					this.incrBlockRequestToTimeSlot(timeSlotId);
					this.decrConcurrentRequest(timeSlotId);
				} else {
					this.getTimeSlot(timeSlotId).incr();
				}
			} else {
				this.getTimeSlot(timeSlotId).incr();
			}
			return !isExceeded;
		} finally {
			w2.unlock();
		}
	}

	/**
	 * Add request RT to the specified time slot
	 * 
	 * @param timeSlotId
	 * @param rt         response time of the request
	 * @param isSuccess  Whether the request is success or not
	 * @return
	 */
	public void addRequestRT(long timeSlotId, long rt, boolean isSuccess) {
		this.getTimeSlot(timeSlotId).addRequestRT(rt, isSuccess);
	}

	/**
	 * Returns statistic of the specified time window
	 * 
	 * @param startSlotId
	 * @param endSlotId
	 * @return
	 */
	public TimeWindowStat getTimeWindowStat(long startSlotId, long endSlotId) {
		TimeWindowStat tws = new TimeWindowStat();

		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		long totalReqs = 0;
		long totalRt = 0;
		long peakConcurrences = 0;
		long errors = 0;
		long blockReqs = 0;
		for (long i = startSlotId; i < endSlotId;) {
			if (timeSlots.containsKey(i)) {
				TimeSlot timeSlot = timeSlots.get(i);
				min = timeSlot.getMin() < min ? timeSlot.getMin() : min;
				max = timeSlot.getMax() > max ? timeSlot.getMax() : max;
				peakConcurrences = timeSlot.getPeakConcurrentReqeusts() > peakConcurrences
						? timeSlot.getPeakConcurrentReqeusts()
						: peakConcurrences;
				totalReqs = totalReqs + timeSlot.getCounter().get();
				totalRt = totalRt + timeSlot.getTotalRt().get();
				errors = errors + timeSlot.getErrors().get();
				blockReqs = blockReqs + timeSlot.getBlockRequests().get();
			}
			i = i + FlowStat.INTERVAL;
		}
		tws.setMin(min == Long.MAX_VALUE ? null : min);
		tws.setMax(max == Long.MIN_VALUE ? null : max);
		tws.setPeakConcurrentReqeusts(peakConcurrences);
		tws.setTotal(totalReqs);
		tws.setErrors(errors);
		tws.setBlockRequests(blockReqs);

		if (totalReqs > 0) {
			tws.setAvgRt(totalRt / totalReqs);

			BigDecimal nsec = new BigDecimal(endSlotId - startSlotId).divide(new BigDecimal(1000), 5,
					BigDecimal.ROUND_HALF_UP);
			BigDecimal rps = new BigDecimal(totalReqs).divide(nsec, 5, BigDecimal.ROUND_HALF_UP);

			if (rps.compareTo(new BigDecimal(10)) >= 0) {
				rps = rps.setScale(0, BigDecimal.ROUND_HALF_UP).stripTrailingZeros();
			} else {
				rps = rps.setScale(2, BigDecimal.ROUND_HALF_UP).stripTrailingZeros();
			}
			tws.setRps(rps);
		}

		return tws;
	}

	public String getResourceId() {
		return resourceId;
	}

	public void setResourceId(String resourceId) {
		this.resourceId = resourceId;
	}

	public ConcurrentMap<Long, TimeSlot> getTimeSlots() {
		return timeSlots;
	}

	public void setTimeSlots(ConcurrentMap<Long, TimeSlot> timeSlots) {
		this.timeSlots = timeSlots;
	}

	public AtomicLong getConcurrentRequests() {
		return concurrentRequests;
	}

	public void setConcurrentRequests(AtomicLong concurrentRequests) {
		this.concurrentRequests = concurrentRequests;
	}

}