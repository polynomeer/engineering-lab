package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.registry.measurement.MeasurementContract;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.pnomeer.lab.registry.measurement.MeasurementContract.*;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonAssessmentTest {
    private static final RunEnvironment ENV = RunEnvironment.capture();
    private static final GitProvenance GIT = new GitProvenance("commit",false,null);
    private static final Measure MEASURE = new Measure("rate",Kind.gauge,"rate","rows/s","worker","interval-rate","one-second-sample");
    private static final MeasurementContract CONTRACT = contract(MEASURE);
    private static final RunExecution LOCAL = new RunExecution(new RunExecution.Definition(RunExecution.Kind.IN_PROCESS,"test",0,RunExecution.MeasurementScope.CONTROLLER_PROCESS),null,RunExecution.Phase.FINALIZING,true);
    private static MeasurementContract contract(Measure m) { return new MeasurementContract(1,"test.experiment","1.0.0",List.of(new Property("integrity","No loss","Inspect CHECK")),List.of(m),List.of("seed"),"Synthetic"); }
    private StoredRun run(String id, String snapshot, Map<String,String> parameters, RunStatus status, Map<String,Double> values, RunEnvironment env) {
        var p = new HashMap<>(parameters); if (snapshot != null) p.put(PARAMETER,snapshot);
        return new StoredRun(1,id,"test.experiment","scenario",status,1,2,3,new RunMetadata(id,p,env,GIT),new ExperimentSummary(Map.of(),values,Map.of()),null);
    }
    private StoredRun run(String id, MeasurementContract c) { return run(id,c == null ? null : c.encode(),Map.of("seed","1"),RunStatus.SUCCEEDED,Map.of("rate",5.0),ENV); }
    private ComparisonAssessment compare(StoredRun a, StoredRun b) { return ComparisonAssessment.of(a,b,LOCAL,LOCAL); }
    private void condition(ComparisonAssessment a, String field, ComparisonAssessment.Match match) {
        assertTrue(a.conditions().stream().anyMatch(c -> c.field().equals(field) && c.status() == match), a.conditions().toString());
    }
    @Test void recordedMatchDoesNotCalculateWinnerOrPropertyVerdict() {
        var a = compare(run("a",CONTRACT),run("b",CONTRACT));
        assertEquals(ComparisonAssessment.State.RECORDED_CONDITIONS_MATCH,a.state());
        assertEquals(5.0,a.measures().getFirst().baselineValue());
        assertEquals(CONTRACT,a.baselineContract());
    }
    @Test void unitsScopeSemanticsAndWindowEachPreventFalseEquivalence() {
        for (var m : List.of(new Measure("rate",Kind.gauge,"rate","items/s","worker","interval-rate","one-second-sample"),
                new Measure("rate",Kind.gauge,"rate","rows/s","controller","interval-rate","one-second-sample"),
                new Measure("rate",Kind.gauge,"rate","rows/s","worker","run-rate","one-second-sample"),
                new Measure("rate",Kind.gauge,"rate","rows/s","worker","interval-rate","whole-run"),
                new Measure("rate",Kind.counter,"rate","rows/s","worker","interval-rate","one-second-sample"))) {
            var a = compare(run("a",CONTRACT),run("b",contract(m)));
            assertEquals(ComparisonAssessment.State.REVIEW_REQUIRED,a.state());
            assertEquals(ComparisonAssessment.Match.MISMATCH,a.measures().getFirst().status());
        }
    }
    @Test void missingValuesLegacyFutureAndFailedRunsRemainUnknown() {
        var a = run("a",CONTRACT);
        for (String snapshot : Arrays.asList(null,"{}",CONTRACT.encode().replace("\"schemaVersion\":1","\"schemaVersion\":9"))) {
            var result = compare(a,run("b",snapshot,Map.of("seed","1"),RunStatus.SUCCEEDED,Map.of("rate",9.0),ENV));
            assertEquals(ComparisonAssessment.State.INSUFFICIENT_EVIDENCE,result.state());
            assertNull(result.candidateContract());
        }
        var noValue = compare(a,run("b",CONTRACT.encode(),Map.of("seed","1"),RunStatus.SUCCEEDED,Map.of(),ENV));
        assertNull(noValue.measures().getFirst().candidateValue());
        assertEquals(ComparisonAssessment.State.INSUFFICIENT_EVIDENCE,noValue.state());
        var failed = compare(a,run("b",CONTRACT.encode(),Map.of("seed","1"),RunStatus.FAILED,Map.of("rate",9.0),ENV));
        condition(failed,"successful-complete-evidence",ComparisonAssessment.Match.UNKNOWN);
        condition(compare(a,a),"distinct-runs",ComparisonAssessment.Match.MISMATCH);
        condition(ComparisonAssessment.of(a,run("b",CONTRACT),null,null),"environment",ComparisonAssessment.Match.UNKNOWN);
    }
    @Test void inputsVariantsAndRequiredMissingParametersAreNeverSilentlyIgnored() {
        var a = run("a",CONTRACT.encode(),Map.of("seed","1","variant","BEFORE","input.sha256","a"),RunStatus.SUCCEEDED,Map.of("rate",5.0),ENV);
        var b = run("b",CONTRACT.encode(),Map.of("seed","2","variant","AFTER","input.sha256","b"),RunStatus.SUCCEEDED,Map.of("rate",5.0),ENV);
        for (String key : List.of("seed","variant","input.sha256")) condition(compare(a,b),"parameters."+key,ComparisonAssessment.Match.MISMATCH);
        condition(compare(run("a",CONTRACT.encode(),Map.of(),RunStatus.SUCCEEDED,Map.of("rate",5.0),ENV),run("b",CONTRACT.encode(),Map.of(),RunStatus.SUCCEEDED,Map.of("rate",5.0),ENV)),"parameters.seed",ComparisonAssessment.Match.UNKNOWN);
    }
    @Test void workerEnvironmentAndArtifactAreComparedWithoutControllerSubstitution() {
        var definition = new RunExecution.Definition(RunExecution.Kind.CONTAINER,"test",1,RunExecution.MeasurementScope.WORKER_PROCESS);
        var worker = new RunExecution.Worker(ENV,GIT,"a".repeat(64));
        var execution = new RunExecution(definition,worker,RunExecution.Phase.FINALIZING,true);
        var otherEnv = new RunEnvironment("other",ENV.javaVendor(),ENV.vmName(),ENV.osName(),ENV.osVersion(),ENV.osArch(),ENV.availableProcessors(),ENV.maxHeapBytes());
        var a = run("a",CONTRACT); var b = run("b",CONTRACT.encode(),Map.of("seed","1"),RunStatus.SUCCEEDED,Map.of("rate",5.0),otherEnv);
        var sameWorker = ComparisonAssessment.of(a,b,execution,execution);
        condition(sameWorker,"environment",ComparisonAssessment.Match.MATCH);
        condition(sameWorker,"external-dependency-environment",ComparisonAssessment.Match.UNKNOWN);
        condition(ComparisonAssessment.of(a,b,execution,new RunExecution(definition,null,RunExecution.Phase.PREPARING,false)),"environment",ComparisonAssessment.Match.UNKNOWN);
        var otherWorker = new RunExecution(definition,new RunExecution.Worker(otherEnv,GIT,"b".repeat(64)),RunExecution.Phase.FINALIZING,true);
        var changed = ComparisonAssessment.of(a,b,execution,otherWorker);
        condition(changed,"environment",ComparisonAssessment.Match.MISMATCH);
        condition(changed,"worker-artifact",ComparisonAssessment.Match.MISMATCH);
        var dirty = new RunExecution(definition,new RunExecution.Worker(ENV,new GitProvenance("commit",true,null),"a".repeat(64)),RunExecution.Phase.FINALIZING,true);
        condition(ComparisonAssessment.of(a,b,execution,dirty),"git",ComparisonAssessment.Match.UNKNOWN);
    }
    @Test void unknownUnitsAndExcessConditionsCannotProduceMatch() {
        var unknown = contract(new Measure("rate",Kind.gauge,"rate","unknown","worker","interval-rate","one-second-sample"));
        condition(compare(run("a",unknown),run("b",unknown)),"measure.rate.unit",ComparisonAssessment.Match.UNKNOWN);
        var many = new HashMap<String,String>(); for (int i=0;i<300;i++) many.put("key"+i,"value");
        var result = compare(run("a",CONTRACT.encode(),many,RunStatus.SUCCEEDED,Map.of("rate",1.0),ENV),run("b",CONTRACT.encode(),many,RunStatus.SUCCEEDED,Map.of("rate",1.0),ENV));
        condition(result,"parameter-source-limit",ComparisonAssessment.Match.UNKNOWN);
        assertTrue(result.conditions().size() < 600);
    }
    @Test void checkOnlyContractsCannotBecomePerformanceEvidence() {
        var check = new MeasurementContract(1,"test.experiment","1.0.0",CONTRACT.properties(),List.of(),List.of("seed"),"CHECK only");
        var a = compare(run("a",check),run("b",check));
        assertEquals(ComparisonAssessment.State.INSUFFICIENT_EVIDENCE,a.state());
        assertTrue(a.measures().isEmpty());
        condition(a,"performance-measures-not-declared",ComparisonAssessment.Match.UNKNOWN);
    }
}
