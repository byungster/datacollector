/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.util;

import com.codahale.metrics.MetricRegistry;
import com.streamsets.dataCollector.execution.PipelineStateStore;
import com.streamsets.dataCollector.execution.Previewer;
import com.streamsets.dataCollector.execution.PreviewerListener;
import com.streamsets.dataCollector.execution.Runner;
import com.streamsets.dataCollector.execution.alerts.AlertManager;
import com.streamsets.dataCollector.execution.manager.PipelineManager;
import com.streamsets.dataCollector.execution.manager.PreviewerProvider;
import com.streamsets.dataCollector.execution.manager.RunnerProvider;
import com.streamsets.dataCollector.execution.runner.AsyncRunner;
import com.streamsets.dataCollector.execution.runner.DataObserverRunnable;
import com.streamsets.dataCollector.execution.runner.MetricObserverRunnable;
import com.streamsets.dataCollector.execution.runner.MetricsObserverRunner;
import com.streamsets.dataCollector.execution.runner.ProductionObserver;
import com.streamsets.dataCollector.execution.runner.StandaloneRunner;
import com.streamsets.dataCollector.execution.store.CachePipelineStateStore;
import com.streamsets.dataCollector.execution.store.FilePipelineStateStore;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseSource;
import com.streamsets.pipeline.api.base.BaseTarget;
import com.streamsets.pipeline.api.base.SingleLaneProcessor;
import com.streamsets.pipeline.api.base.SingleLaneRecordProcessor;
import com.streamsets.pipeline.config.DataRuleDefinition;
import com.streamsets.pipeline.config.MetricsRuleDefinition;
import com.streamsets.pipeline.config.PipelineConfiguration;
import com.streamsets.pipeline.config.RuleDefinitions;
import com.streamsets.pipeline.config.ThresholdType;
import com.streamsets.pipeline.email.EmailSender;
import com.streamsets.pipeline.lib.executor.SafeScheduledExecutorService;
import com.streamsets.pipeline.main.RuntimeInfo;
import com.streamsets.pipeline.main.RuntimeModule;
import com.streamsets.pipeline.prodmanager.PipelineManagerException;
import com.streamsets.pipeline.prodmanager.StandalonePipelineManagerTask;
import com.streamsets.pipeline.prodmanager.State;
import com.streamsets.pipeline.runner.MockStages;
import com.streamsets.pipeline.runner.Observer;
import com.streamsets.pipeline.runner.SourceOffsetTracker;
import com.streamsets.pipeline.runner.production.ProductionPipelineBuilder;
import com.streamsets.pipeline.runner.production.ProductionPipelineRunner;
import com.streamsets.pipeline.runner.production.ProductionSourceOffsetTracker;
import com.streamsets.pipeline.runner.production.RulesConfigLoader;
import com.streamsets.pipeline.runner.production.RulesConfigLoaderRunnable;
import com.streamsets.pipeline.runner.production.ThreadHealthReporter;
import com.streamsets.pipeline.snapshotstore.SnapshotStore;
import com.streamsets.pipeline.snapshotstore.impl.FileSnapshotStore;
import com.streamsets.pipeline.stagelibrary.StageLibraryTask;
import com.streamsets.pipeline.store.PipelineStoreException;
import com.streamsets.pipeline.store.PipelineStoreTask;
import com.streamsets.pipeline.store.impl.FilePipelineStoreTask;
import dagger.Module;
import dagger.ObjectGraph;
import dagger.Provides;
import org.mockito.Mockito;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class TestUtil {
  public static final String MY_PIPELINE = "my pipeline";
  public static final String MY_SECOND_PIPELINE = "my second pipeline";
  public static final String PIPELINE_REV = "2.0";
  public static boolean EMPTY_OFFSET = false;

  public static class SourceOffsetTrackerImpl implements SourceOffsetTracker {
    private String currentOffset;
    private String newOffset;
    private boolean finished;
    private long lastBatchTime;

    public SourceOffsetTrackerImpl(String currentOffset) {
      this.currentOffset = currentOffset;
      finished = false;
    }

    @Override
    public boolean isFinished() {
      return finished;
    }

    @Override
    public String getOffset() {
      return currentOffset;
    }

    @Override
    public void setOffset(String newOffset) {
      this.newOffset = newOffset;
    }

    @Override
    public void commitOffset() {
      currentOffset = newOffset;
      finished = (currentOffset == null);
      newOffset = null;
      lastBatchTime = System.currentTimeMillis();
    }

    @Override
    public long getLastBatchTime() {
      return lastBatchTime;
    }
  }


  /********************************************/
  /********* Pipeline using Mock Stages *******/
  /********************************************/

  public static void captureMockStages() {
    MockStages.setSourceCapture(new BaseSource() {
      private int recordsProducedCounter = 0;

      @Override
      public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
        Record record = getContext().createRecord("x");
        record.set(Field.create(1));
        batchMaker.addRecord(record);
        recordsProducedCounter++;
        if (recordsProducedCounter == 1) {
          recordsProducedCounter = 0;
          return null;
        }
        return "1";
      }
    });
    MockStages.setProcessorCapture(new SingleLaneRecordProcessor() {
      @Override
      protected void process(Record record, SingleLaneBatchMaker batchMaker) throws StageException {
        record.set(Field.create(2));
        batchMaker.addRecord(record);
      }
    });
    MockStages.setTargetCapture(new BaseTarget() {
      @Override
      public void write(Batch batch) throws StageException {
      }
    });
  }

  public static void captureStagesForProductionRun() {
    MockStages.setSourceCapture(new BaseSource() {

      @Override
      public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
        maxBatchSize = (maxBatchSize > -1) ? maxBatchSize : 10;
        for (int i = 0; i < maxBatchSize; i++ ) {
          batchMaker.addRecord(createRecord(lastSourceOffset, i));
        }
        return EMPTY_OFFSET  == true ? null: "random";
      }

      private Record createRecord(String lastSourceOffset, int batchOffset) {
        Record record = getContext().createRecord("random:" + batchOffset);
        Map<String, Field> map = new HashMap<>();
        map.put("name", Field.create(UUID.randomUUID().toString()));
        map.put("time", Field.create(System.currentTimeMillis()));
        record.set(Field.create(map));
        return record;
      }
    });
    MockStages.setProcessorCapture(new SingleLaneProcessor() {
      private Random random;

      @Override
      protected void init() throws StageException {
        super.init();
        random = new Random();
      }

      @Override
      public void process(Batch batch, SingleLaneBatchMaker batchMaker) throws
          StageException {
        Iterator<Record> it = batch.getRecords();
        while (it.hasNext()) {
          float action = random.nextFloat();
          getContext().toError(it.next(), "Random error");
        }
        getContext().reportError("Random pipeline error");
      }
    });

    MockStages.setTargetCapture(new BaseTarget() {
      @Override
      public void write(Batch batch) throws StageException {
      }
    });
  }

  /********************************************/
  /*************** Providers for Dagger *******/
  /********************************************/


  /*************** StageLibrary ***************/

  @Module(library = true)
  public static class TestStageLibraryModule {

    public TestStageLibraryModule() {
    }

    @Provides @Singleton
    public StageLibraryTask provideStageLibrary() {
      return MockStages.createStageLibrary();
    }
  }

  /*************** PipelineStore ***************/

  @Module(injects = PipelineStoreTask.class, library = true, includes = {TestRuntimeModule.class,
    TestStageLibraryModule.class, TestConfigurationModule.class, TestPipelineStateStoreModule.class})
  public static class TestPipelineStoreModule {

    public TestPipelineStoreModule() {
    }

    @Provides
    public PipelineStoreTask providePipelineStore(RuntimeInfo info, StageLibraryTask stageLibraryTask) {
      FilePipelineStoreTask pipelineStoreTask = new FilePipelineStoreTask(info, stageLibraryTask, null);
      pipelineStoreTask.init();
      try {
        //create an invalid pipeline
        pipelineStoreTask.create("user", "invalid", "invalid cox its empty");
        PipelineConfiguration pipelineConf = pipelineStoreTask.load("invalid", PIPELINE_REV);
        PipelineConfiguration mockPipelineConf = MockStages.createPipelineConfigurationSourceProcessorTarget();
        pipelineConf.setErrorStage(mockPipelineConf.getErrorStage());

        //create a valid pipeline
        pipelineStoreTask.create("user", MY_PIPELINE, "description");
        pipelineConf = pipelineStoreTask.load(MY_PIPELINE, PIPELINE_REV);
        mockPipelineConf = MockStages.createPipelineConfigurationSourceProcessorTarget();
        pipelineConf.setStages(mockPipelineConf.getStages());
        pipelineConf.setErrorStage(mockPipelineConf.getErrorStage());
        pipelineStoreTask.save("admin", MY_PIPELINE, PIPELINE_REV, "description"
          , pipelineConf);

        //create a DataRuleDefinition for one of the stages
        DataRuleDefinition dataRuleDefinition = new DataRuleDefinition("myID", "myLabel", "s", 100, 10,
          "${record:value(\"/name\") != null}", true, "alertText", ThresholdType.COUNT, "100", 100, true, false, true);
        List<DataRuleDefinition> dataRuleDefinitions = new ArrayList<>();
        dataRuleDefinitions.add(dataRuleDefinition);

        RuleDefinitions ruleDefinitions = new RuleDefinitions(Collections.<MetricsRuleDefinition>emptyList(),
          dataRuleDefinitions, Collections.<String>emptyList(), UUID.randomUUID());
        pipelineStoreTask.storeRules(MY_PIPELINE, PIPELINE_REV, ruleDefinitions);

      } catch (PipelineStoreException e) {
        throw new RuntimeException(e);
      }

      return pipelineStoreTask;
    }
  }

  /*************** PipelineStore ***************/
  // TODO - Rename TestPipelineStoreModule after multi pipeline support
  @Module(injects = PipelineStoreTask.class, library = true, includes = {TestRuntimeModule.class,
    TestStageLibraryModule.class, TestConfigurationModule.class, TestPipelineStateStoreModule.class })
  public static class TestPipelineStoreModuleNew {

    public TestPipelineStoreModuleNew() {

    }

    @Provides @Singleton
    public PipelineStoreTask providePipelineStore(RuntimeInfo info, StageLibraryTask stageLibraryTask, PipelineStateStore pipelineStateStore) {
      FilePipelineStoreTask pipelineStoreTask = new FilePipelineStoreTask(info, stageLibraryTask, pipelineStateStore);
      pipelineStoreTask.init();
      try {
        //create an invalid pipeline
        //The if check is needed because the tests restart the pipeline manager. In that case the check prevents
        //us from trying to create the same pipeline again
        if(!pipelineStoreTask.hasPipeline("invalid")) {
          pipelineStoreTask.create("user", "invalid", "invalid cox its empty");
          PipelineConfiguration pipelineConf = pipelineStoreTask.load("invalid", PIPELINE_REV);
          PipelineConfiguration mockPipelineConf = MockStages.createPipelineConfigurationSourceTarget();
          pipelineConf.setErrorStage(mockPipelineConf.getErrorStage());
        }

        if(!pipelineStoreTask.hasPipeline(MY_PIPELINE)) {
          pipelineStoreTask.create("user", MY_PIPELINE, "description");
          PipelineConfiguration pipelineConf = pipelineStoreTask.load(MY_PIPELINE, "0");
          PipelineConfiguration mockPipelineConf = MockStages.createPipelineConfigurationSourceTarget();
          pipelineConf.setStages(mockPipelineConf.getStages());
          pipelineConf.setErrorStage(mockPipelineConf.getErrorStage());
          pipelineStoreTask.save("admin", MY_PIPELINE, "0", "description"
            , pipelineConf);

          //create a DataRuleDefinition for one of the stages
          DataRuleDefinition dataRuleDefinition = new DataRuleDefinition("myID", "myLabel", "s", 100, 10,
            "${record:value(\"/name\") != null}", true, "alertText", ThresholdType.COUNT, "100", 100, true, false, true);
          List<DataRuleDefinition> dataRuleDefinitions = new ArrayList<>();
          dataRuleDefinitions.add(dataRuleDefinition);

          RuleDefinitions ruleDefinitions = new RuleDefinitions(Collections.<MetricsRuleDefinition>emptyList(),
            dataRuleDefinitions, Collections.<String>emptyList(), UUID.randomUUID());
          pipelineStoreTask.storeRules(MY_PIPELINE, "0", ruleDefinitions);
        }

        if(!pipelineStoreTask.hasPipeline(MY_SECOND_PIPELINE)) {
          pipelineStoreTask.create("user2", MY_SECOND_PIPELINE, "description2");
          PipelineConfiguration pipelineConf = pipelineStoreTask.load(MY_SECOND_PIPELINE, "0");
          PipelineConfiguration mockPipelineConf = MockStages.createPipelineConfigurationSourceProcessorTarget();
          pipelineConf.setStages(mockPipelineConf.getStages());
          pipelineConf.setErrorStage(mockPipelineConf.getErrorStage());
          pipelineStoreTask.save("admin2", MY_SECOND_PIPELINE, "0", "description"
            , pipelineConf);
        }
      } catch (PipelineStoreException e) {
        throw new RuntimeException(e);
      }

      return pipelineStoreTask;
    }
  }

  /*************** PipelineStateStore ***************/

  @Module(injects = PipelineStateStore.class, library = true, includes = {TestRuntimeModule.class,
    TestConfigurationModule.class})
  public static class TestPipelineStateStoreModule {
    public TestPipelineStateStoreModule() {
    }

    @Provides @Singleton
    public PipelineStateStore providePipelineStore(RuntimeInfo info, Configuration conf) {
      PipelineStateStore pipelineStateStore = new FilePipelineStateStore(info, conf);
      CachePipelineStateStore cachePipelineStateStore = new CachePipelineStateStore(pipelineStateStore);
      cachePipelineStateStore.init();
      return cachePipelineStateStore;
    }
  }

  /*************** Configuration ***************/

  @Module(library = true)
  public static class TestConfigurationModule {

    public TestConfigurationModule() {
    }

    @Provides  @Singleton
    public Configuration provideConfiguration() {
      Configuration conf = new Configuration();
      return conf;
    }
  }

  /*************** RuntimeInfo ***************/

  @Module(library = true)
  public static class TestRuntimeModule {

    public TestRuntimeModule() {
    }

    @Provides @Singleton
    public RuntimeInfo provideRuntimeInfo() {
      RuntimeInfo info = new RuntimeInfo(RuntimeModule.SDC_PROPERTY_PREFIX, new MetricRegistry(),
        Arrays.asList(getClass().getClassLoader()));
      return info;
    }

  }

  /*************** SafeScheduledExecutorService ***************/

  @Module(library = true)
  public static class TestExecutorModule {

    @Provides @Named("previewExecutor")
    public SafeScheduledExecutorService providePreviewExecutor() {
      return new SafeScheduledExecutorService(1, "preview");
    }

    @Provides @Named("runnerExecutor") @Singleton
    public SafeScheduledExecutorService provideRunnerExecutor() {
      return new SafeScheduledExecutorService(10, "runner");

    }

    @Provides @Named("managerExecutor") @Singleton
    public SafeScheduledExecutorService provideManagerExecutor() {
      return new SafeScheduledExecutorService(10, "manager");

    }
  }

  /*************** PipelineProvider ***************/

  @Module(injects = {EmailSender.class, AlertManager.class, ProductionObserver.class, RulesConfigLoader.class,
    ThreadHealthReporter.class, DataObserverRunnable.class, RulesConfigLoaderRunnable.class, MetricObserverRunnable.class,
    SourceOffsetTracker.class, ProductionPipelineRunner.class, ProductionPipelineBuilder.class},
    library = true, includes = {TestRuntimeModule.class, TestPipelineStoreModuleNew.class})
  public static class TestPipelineProviderModule {

    private String name;
    private String rev;


    public TestPipelineProviderModule() {
    }

    public TestPipelineProviderModule(String name, String rev) {
      this.name = name;
      this.rev = rev;
    }

    @Provides
    @Named("name")
    public String provideName() {
      return name;
    }

    @Provides
    @Named("rev")
    public String provideRev() {
      return rev;
    }

    @Provides @Singleton
    public MetricRegistry provideMetricRegistry() {
      return new MetricRegistry();
    }

    @Provides @Singleton
    public EmailSender provideEmailSender() {
      return Mockito.mock(EmailSender.class);
    }

    @Provides @Singleton
    public AlertManager provideAlertManager() {
      return Mockito.mock(AlertManager.class);
    }

    @Provides @Singleton
    public MetricsObserverRunner provideMetricsObserverRunner() {
      return Mockito.mock(MetricsObserverRunner.class);
    }

    @Provides @Singleton
    public Observer provProductionObserver() {
      return Mockito.mock(ProductionObserver.class);
    }

    @Provides @Singleton
    public RulesConfigLoader provideRulesConfigLoader() {
      return Mockito.mock(RulesConfigLoader.class);
    }

    @Provides @Singleton
    public ThreadHealthReporter provideThreadHealthReporter() {
      return Mockito.mock(ThreadHealthReporter.class);
    }

    @Provides @Singleton
    public RulesConfigLoaderRunnable provideRulesConfigLoaderRunnable() {
      return Mockito.mock(RulesConfigLoaderRunnable.class);
    }

    @Provides @Singleton
    public MetricObserverRunnable provideMetricObserverRunnable() {
      return Mockito.mock(MetricObserverRunnable.class);
    }

    @Provides @Singleton
    public DataObserverRunnable provideDataObserverRunnable() {
      return Mockito.mock(DataObserverRunnable.class);
    }

    @Provides @Singleton
    public SourceOffsetTracker provideProductionSourceOffsetTracker(@Named("name") String name,
                                                                              @Named("rev") String rev,
                                                                              RuntimeInfo runtimeInfo) {
      return new ProductionSourceOffsetTracker(name, rev, runtimeInfo);
    }

    @Provides @Singleton
    public ProductionPipelineRunner provideProductionPipelineRunner(@Named("name") String name,
                                                                    @Named("rev") String rev, Configuration configuration, RuntimeInfo runtimeInfo,
                                                                    MetricRegistry metrics, SnapshotStore snapshotStore,
                                                                    ThreadHealthReporter threadHealthReporter,
                                                                    SourceOffsetTracker sourceOffsetTracker) {
      return new ProductionPipelineRunner(name, rev, configuration, runtimeInfo, metrics, snapshotStore,
        threadHealthReporter, sourceOffsetTracker);
    }

    @Provides @Singleton
    public ProductionPipelineBuilder provideProductionPipelineBuilder(@Named("name") String name,
                                                                      @Named("rev") String rev,
                                                                      RuntimeInfo runtimeInfo, StageLibraryTask stageLib,
                                                                      ProductionPipelineRunner runner, Observer observer) {
      return new ProductionPipelineBuilder(name, rev, runtimeInfo, stageLib, runner, observer);
    }

    @Provides @Singleton
    public SnapshotStore provideSnapshotStore(RuntimeInfo runtimeInfo) {
      return new FileSnapshotStore(runtimeInfo);
    }
  }

  /*************** Runner ***************/

  @Module(injects = Runner.class, library = true, includes = {TestExecutorModule.class, TestPipelineStoreModuleNew.class,
    TestPipelineStateStoreModule.class, TestPipelineProviderModule.class})
  public static class TestRunnerModule {

    private final String name;
    private final String rev;
    private final String user;
    private final ObjectGraph objectGraph;

    public TestRunnerModule(String user, String name, String rev, ObjectGraph objectGraph) {
      this.name = name;
      this.rev = rev;
      this.user = user;
      this.objectGraph = objectGraph;
    }

    @Provides
    public Runner provideRunner(@Named("runnerExecutor") SafeScheduledExecutorService runnerExecutor) {
      return new AsyncRunner(new StandaloneRunner(user, name, rev, objectGraph), runnerExecutor);
    }
  }

  /*************** PipelineManager ***************/

  @Module(injects = {PipelineManager.class, StandaloneRunner.class}, library = true,
    includes = { TestRuntimeModule.class, TestPipelineStoreModuleNew.class, TestPipelineStateStoreModule.class,
      TestStageLibraryModule.class, TestConfigurationModule.class, TestExecutorModule.class, TestPipelineProviderModule.class})
  public static class TestPipelineManagerModule {

    public TestPipelineManagerModule() {
    }

    @Provides @Singleton
    public PreviewerProvider providePreviewerProvider() {
      return new PreviewerProvider() {
        @Override
        public Previewer createPreviewer(String user, String name, String rev, PreviewerListener listener,
                                         ObjectGraph objectGraph) {
          Previewer mock = Mockito.mock(Previewer.class);
          Mockito.when(mock.getId()).thenReturn(UUID.randomUUID().toString());
          Mockito.when(mock.getName()).thenReturn(name);
          Mockito.when(mock.getRev()).thenReturn(rev);
          return mock;
        }
      };
    }

    @Provides @Singleton
    public RunnerProvider provideRunnerProvider() {
      return new RunnerProvider() {
        @Override
        public Runner createRunner(String user, String name, String rev, PipelineConfiguration pipelineConf,
                                   ObjectGraph objectGraph) {
          ObjectGraph plus = objectGraph.plus(new TestPipelineProviderModule(name, rev));
          TestRunnerModule testRunnerModule = new TestRunnerModule(user, name, rev, plus);
          return testRunnerModule.provideRunner(new SafeScheduledExecutorService(1, "asyncExecutor"));
        }
      };
    }

  }

  /*************** StandalonePipelineManagerTask ***************/

  @Module(injects = StandalonePipelineManagerTask.class
    , library = true, includes = {TestRuntimeModule.class, TestPipelineStoreModule.class
    , TestStageLibraryModule.class, TestConfigurationModule.class})
  public static class TestProdManagerModule {

    public TestProdManagerModule() {
    }

    @Provides
    public StandalonePipelineManagerTask provideStateManager(RuntimeInfo RuntimeInfo, Configuration configuration
      ,PipelineStoreTask pipelineStore, StageLibraryTask stageLibrary) {
      return new StandalonePipelineManagerTask(RuntimeInfo, configuration, pipelineStore, stageLibrary);
    }
  }


  /********************************************/
  /*************** Utility methods ************/
  /********************************************/

  public static void stopPipelineIfNeeded(StandalonePipelineManagerTask manager) throws InterruptedException, PipelineManagerException {
    if(manager.getPipelineState().getState() == State.RUNNING) {
      manager.stopPipeline(false);
    }
    long start = System.currentTimeMillis();
    while(manager.getPipelineState().getState() != State.FINISHED &&
      manager.getPipelineState().getState() != State.STOPPED &&
      manager.getPipelineState().getState() != State.ERROR) {
      Thread.sleep(5);
      long elapsed = System.currentTimeMillis() - start;
      if (elapsed > TimeUnit.MINUTES.toMillis(5)) {
        String msg = "TimedOut waiting for pipeline to stop. State is currently: " +
          manager.getPipelineState().getState() + " after " + TimeUnit.MILLISECONDS.toMinutes(elapsed) + "min";
        throw new IllegalStateException(msg);
      }
    }
  }

}
