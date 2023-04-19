package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final PageParserFactory parserFactory;
  private final Duration timeout;
  private final int popularWordCount;
  private final int maxDepth;
  private final List<Pattern> ignoredUrls;
  private final ForkJoinPool pool;
  public static Lock lock = new ReentrantLock();

  @Inject
  ParallelWebCrawler(
      Clock clock,
      PageParserFactory parserFactory,
      @Timeout Duration timeout,
      @PopularWordCount int popularWordCount,
      @MaxDepth int maxDepth,
      @IgnoredUrls List<Pattern> ignoredUrls,
      @TargetParallelism int threadCount) {
    this.clock = clock;
    this.parserFactory = parserFactory;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.maxDepth = maxDepth;
    this.ignoredUrls = ignoredUrls;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    //TO DONE: Complete this.
    Instant deadline = clock.instant().plus(timeout);
    ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
    ConcurrentSkipListSet<String> visitedUrls = new ConcurrentSkipListSet<>();
    for (String url : startingUrls) {
      pool.invoke(crawlInternal.Builder.newInstance()
              .setUrl(url)
              .setDeadline(deadline)
              .setMaxDepth(maxDepth)
              .setCounts(counts)
              .setVisitedUrls(visitedUrls)
              .setClock(clock)
              .setParserFactory(parserFactory)
              .setIgnoredUrls(ignoredUrls)
              .build());
    }
    if (counts.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(counts)
              .setUrlsVisited(visitedUrls.size())
              .build();
    }
    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counts, popularWordCount))
            .setUrlsVisited(visitedUrls.size())
            .build();
  }

  public static class crawlInternal extends RecursiveAction {

    private final String url;
    private final Instant deadline;
    private final int maxDepth;
    private final ConcurrentHashMap<String, Integer> counts;
    private final ConcurrentSkipListSet<String> visitedUrls;
    private final Clock clock;
    private final PageParserFactory parserFactory;
    private final List<Pattern> ignoredUrls;

    public crawlInternal(Builder builder)
    {
      this.url = builder.url;
      this.deadline = builder.deadline;
      this.maxDepth = builder.maxDepth;
      this.counts = builder.counts;
      this.visitedUrls = builder.visitedUrls;
      this.clock = builder.clock;
      this.parserFactory = builder.parserFactory;
      this.ignoredUrls = builder.ignoredUrls;
    }

    // Static class Builder
    public static class Builder {

      /// instance fields
      private String url;
      private Instant deadline;
      private int maxDepth;
      private ConcurrentHashMap<String, Integer> counts;
      private ConcurrentSkipListSet<String> visitedUrls;
      private Clock clock;
      private PageParserFactory parserFactory;
      private List<Pattern> ignoredUrls;

      public static Builder newInstance()
      {
        return new Builder();
      }

      private Builder() {}

      // Setter methods
      public Builder setUrl(String url)
      {
        this.url = url;
        return this;
      }
      public Builder setDeadline(Instant deadline)
      {
        this.deadline = deadline;
        return this;
      }

      public Builder setMaxDepth(int maxDepth)
      {
        this.maxDepth = maxDepth;
        return this;
      }

      public Builder setCounts(ConcurrentHashMap<String, Integer> counts)
      {
        this.counts = counts;
        return this;
      }

      public Builder setVisitedUrls(ConcurrentSkipListSet<String> visitedUrls)
      {
        this.visitedUrls = visitedUrls;
        return this;
      }

      public Builder setClock(Clock clock)
      {
        this.clock = clock;
        return this;
      }

      public Builder setParserFactory(PageParserFactory parserFactory)
      {
        this.parserFactory = parserFactory;
        return this;
      }

      public Builder setIgnoredUrls(List<Pattern> ignoredUrls)
      {
        this.ignoredUrls = ignoredUrls;
        return this;
      }

      public crawlInternal build()
      {
        return new crawlInternal(this);
      }
    }

    @Override
    protected void compute() {
      // Basic configuration checks
      if (maxDepth == 0) {
        // Reached maximum link following depth
        return;
      }
      if (clock.instant().isAfter(deadline)) {
        // Ran out of time
        return;
      }
      for (Pattern pattern : ignoredUrls) {
        if (pattern.matcher(url).matches()) {
          // This URL gets ignored
          return;
        }
      }

      // Verify that we haven't yet traversed the current URL
      try {
        lock.lock();
        if (visitedUrls.contains(url)) {
          return;
        }
        visitedUrls.add(url);
      } catch (Exception e) {
        e.printStackTrace();
      } finally {
        lock.unlock();
      }

      // Parse the URL and add results
      PageParser.Result result = parserFactory.get(url).parse();
      for (ConcurrentHashMap.Entry<String, Integer> resultSet : result.getWordCounts().entrySet()) {
        counts.compute(resultSet.getKey(), (key, value) -> (value == null) ? resultSet.getValue() : resultSet.getValue() + value);
      }

      // Create recursive tasks for all links encountered at this URL
      List<crawlInternal> recursiveTasks = new ArrayList<>();
      for (String innerUrl : result.getLinks()) {
        recursiveTasks.add(crawlInternal.Builder.newInstance()
                .setUrl(innerUrl)
                .setDeadline(deadline)
                .setMaxDepth(maxDepth - 1)
                .setCounts(counts)
                .setVisitedUrls(visitedUrls)
                .setClock(clock)
                .setParserFactory(parserFactory)
                .setIgnoredUrls(ignoredUrls)
                .build());
      }
      invokeAll(recursiveTasks);
    }
  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }
}
