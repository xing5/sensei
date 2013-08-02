package com.senseidb.svc.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.Logger;
import org.apache.lucene.util.NamedThreadFactory;

import proj.zoie.api.IndexReaderFactory;
import proj.zoie.api.ZoieMultiReader;
import zu.finagle.serialize.ZuSerializer;

import com.browseengine.bobo.api.BoboSegmentReader;
import com.senseidb.metrics.MetricsConstants;
import com.senseidb.search.node.SenseiCore;
import com.senseidb.search.node.SenseiQueryBuilderFactory;
import com.senseidb.search.req.AbstractSenseiRequest;
import com.senseidb.search.req.AbstractSenseiResult;
import com.senseidb.search.req.ErrorType;
import com.senseidb.search.req.SenseiError;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.Timer;

public abstract class AbstractSenseiCoreService<Req extends AbstractSenseiRequest, Res extends AbstractSenseiResult> {
  private final static Logger logger = Logger.getLogger(AbstractSenseiCoreService.class);

  private static Timer GetReaderTimer = null;
  private static Timer SearchTimer = null;
  private static Timer MergeTimer = null;
  private static Meter SearchCounter = null;
  static {
    // register jmx monitoring for timers
    try {
      MetricName getReaderMetricName = new MetricName(MetricsConstants.Domain, "timer",
          "getreader-time", "node");
      GetReaderTimer = Metrics.newTimer(getReaderMetricName, TimeUnit.MILLISECONDS,
        TimeUnit.SECONDS);

      MetricName searchMetricName = new MetricName(MetricsConstants.Domain, "timer", "search-time",
          "node");
      SearchTimer = Metrics.newTimer(searchMetricName, TimeUnit.MILLISECONDS, TimeUnit.SECONDS);

      MetricName mergeMetricName = new MetricName(MetricsConstants.Domain, "timer", "merge-time",
          "node");
      MergeTimer = Metrics.newTimer(mergeMetricName, TimeUnit.MILLISECONDS, TimeUnit.SECONDS);

      MetricName searchCounterMetricName = new MetricName(MetricsConstants.Domain, "meter",
          "search-count", "node");
      SearchCounter = Metrics.newMeter(searchCounterMetricName, "requets", TimeUnit.SECONDS);

    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }
  }
  protected long _timeout = 8000;

  protected final SenseiCore _core;

  private final NamedThreadFactory threadFactory = new NamedThreadFactory("parallel-searcher");
  private final ExecutorService _executorService = Executors.newCachedThreadPool(threadFactory);

  private final Map<Integer, Timer> partitionTimerMetricMap = new HashMap<Integer, Timer>();
  protected final Map<Integer, Counter> partitionCalls = new HashMap<Integer, Counter>();

  public AbstractSenseiCoreService(SenseiCore core) {
    _core = core;
    initCounters();
  }

  private Timer buildTimer(int partition) {
    MetricName partitionSearchMetricName = new MetricName(MetricsConstants.Domain, "timer",
        "partition-time-" + partition, "partition");
    return Metrics.newTimer(partitionSearchMetricName, TimeUnit.MILLISECONDS, TimeUnit.SECONDS);
  }

  private Timer getTimer(int partition) {
    Timer timer = partitionTimerMetricMap.get(partition);
    if (timer == null) {
      partitionTimerMetricMap.put(partition, buildTimer(partition));
      return getTimer(partition);
    }
    return timer;
  }

  @SuppressWarnings("unchecked")
  public Res execute(final Req senseiReq) {
    SearchCounter.mark();
    Set<Integer> partitions = senseiReq == null ? null : senseiReq.getPartitions();
    if (partitions == null) {
      partitions = new HashSet<Integer>();
      int[] containsPart = _core.getPartitions();
      if (containsPart != null) {
        for (int part : containsPart) {
          partitions.add(part);
        }
      }
    }
    Res finalResult;
    if (partitions != null && partitions.size() > 0) {
      if (logger.isDebugEnabled()) {
        logger.debug("serving partitions: " + partitions.toString());
      }
      // we need to release index readers from all partitions only after the merge step
      final Map<IndexReaderFactory<BoboSegmentReader>, List<ZoieMultiReader<BoboSegmentReader>>> indexReaderCache = new ConcurrentHashMap<IndexReaderFactory<BoboSegmentReader>, List<ZoieMultiReader<BoboSegmentReader>>>();
      try {
        final ArrayList<Res> resultList = new ArrayList<Res>(partitions.size());
        Future<Res>[] futures = new Future[partitions.size() - 1];
        int i = 0;

        for (final int partition : partitions) {
          final long start = System.currentTimeMillis();
          final IndexReaderFactory<BoboSegmentReader> readerFactory = _core
              .getIndexReaderFactory(partition);

          if (i < partitions.size() - 1) // Search simultaneously.
          {
            try {
              futures[i] = _executorService.submit(new Callable<Res>() {
                @Override
                public Res call() throws Exception {
                  Timer timer = getTimer(partition);

                  Res res = timer.time(new Callable<Res>() {

                    @Override
                    public Res call() throws Exception {
                      incrementCallCounter(partition);
                      return handleRequest(senseiReq, readerFactory,
                        _core.getQueryBuilderFactory(), indexReaderCache);
                    }
                  });

                  long end = System.currentTimeMillis();
                  res.setTime(end - start);
                  logger.info("searching partition: " + partition + " browse took: "
                      + res.getTime());

                  return res;
                }
              });
            } catch (Exception e) {
              senseiReq.addError(new SenseiError(e.getMessage(), ErrorType.BoboExecutionError));
              logger.error(e.getMessage(), e);
            }
          } else // Reuse current thread.
          {
            try {
              Timer timer = getTimer(partition);
              Res res = timer.time(new Callable<Res>() {

                @Override
                public Res call() throws Exception {
                  incrementCallCounter(partition);
                  return handleRequest(senseiReq, readerFactory, _core.getQueryBuilderFactory(),
                    indexReaderCache);
                }
              });

              resultList.add(res);
              long end = System.currentTimeMillis();
              res.setTime(end - start);
              logger.info("searching partition: " + partition + " browse took: " + res.getTime());
            } catch (Exception e) {
              logger.error(e.getMessage(), e);
              senseiReq.addError(new SenseiError(e.getMessage(), ErrorType.BoboExecutionError));

              resultList.add(getEmptyResultInstance(e));
            }
          }
          ++i;
        }

        for (i = 0; i < futures.length; ++i) {
          try {
            Res res = futures[i].get(_timeout, TimeUnit.MILLISECONDS);
            resultList.add(res);
          } catch (Exception e) {

            logger.error(e.getMessage(), e);
            if (e instanceof TimeoutException) {
              senseiReq.addError(new SenseiError(e.getMessage(), ErrorType.ExecutionTimeout));
            } else {
              senseiReq.addError(new SenseiError(e.getMessage(), ErrorType.BoboExecutionError));
            }
            resultList.add(getEmptyResultInstance(e));
          }
        }

        try {
          finalResult = MergeTimer.time(new Callable<Res>() {
            @Override
            public Res call() throws Exception {
              return mergePartitionedResults(senseiReq, resultList);
            }
          });
        } catch (Exception e) {
          logger.error(e.getMessage(), e);
          finalResult = getEmptyResultInstance(null);
          finalResult.addError(new SenseiError(e.getMessage(), ErrorType.MergePartitionError));
        }
      } finally {
        returnIndexReaders(indexReaderCache);
      }
    }

    else {
      if (logger.isInfoEnabled()) {
        logger.info("no partitions specified");
      }
      finalResult = getEmptyResultInstance(null);
      finalResult
          .addError(new SenseiError("no partitions specified", ErrorType.PartitionCallError));
    }
    if (logger.isInfoEnabled()) {
      logger.info("searching partitions: " + String.valueOf(partitions) + "; route by: "
          + senseiReq.getRouteParam() + "; took: " + finalResult.getTime());
    }
    return finalResult;
  }

  private void returnIndexReaders(
      Map<IndexReaderFactory<BoboSegmentReader>, List<ZoieMultiReader<BoboSegmentReader>>> indexReaderCache) {
    for (IndexReaderFactory<BoboSegmentReader> indexReaderFactory : indexReaderCache.keySet()) {
      indexReaderFactory.returnIndexReaders(indexReaderCache.get(indexReaderFactory));
    }

  }

  private final Res handleRequest(
      final Req senseiReq,
      final IndexReaderFactory<BoboSegmentReader> readerFactory,
      final SenseiQueryBuilderFactory queryBuilderFactory,
      Map<IndexReaderFactory<BoboSegmentReader>, List<ZoieMultiReader<BoboSegmentReader>>> indexReadersToCleanUp)
      throws Exception {
    List<ZoieMultiReader<BoboSegmentReader>> readerList = null;
    readerList = GetReaderTimer.time(new Callable<List<ZoieMultiReader<BoboSegmentReader>>>() {
      @Override
      public List<ZoieMultiReader<BoboSegmentReader>> call() throws Exception {
        if (readerFactory == null) {
          return Collections.emptyList();
        }
        return readerFactory.getIndexReaders();
      }
    });
    if (logger.isDebugEnabled()) {
      logger.debug("obtained readerList of size: " + readerList == null ? 0 : readerList.size());
    }

    if (readerFactory != null && readerList != null) {
      indexReadersToCleanUp.put(readerFactory, readerList);
    }
    final List<BoboSegmentReader> boboReaders = ZoieMultiReader.extractDecoratedReaders(readerList);

    return SearchTimer.time(new Callable<Res>() {
      @Override
      public Res call() throws Exception {
        return handlePartitionedRequest(senseiReq, boboReaders, queryBuilderFactory);
      }
    });
  }

  public abstract Res handlePartitionedRequest(Req r, final List<BoboSegmentReader> readerList,
      SenseiQueryBuilderFactory queryBuilderFactory) throws Exception;

  public abstract Res mergePartitionedResults(Req r, List<Res> reqList);

  public abstract Res getEmptyResultInstance(Throwable error);

  public abstract ZuSerializer<Req, Res> getSerializer();

  public abstract String getMessageTypeName();

  public void incrementCallCounter(final int partition) {
    partitionCalls.get(partition).inc();
  }

  private void initCounters() {
    for (int currentPartition : _core.getPartitions()) {
      partitionCalls.put(
        currentPartition,
        Metrics.newCounter(AbstractSenseiCoreService.class, "partitionCallsForPartition"
            + currentPartition + "andNode" + _core.getNodeId()));
    }
  }
}
