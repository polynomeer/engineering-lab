package com.pnomeer.lab.experiments.career;

import com.pnomeer.lab.runtime.*;
import com.pnomeer.lab.worker.WireJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EquityMysqlResourceRecoveryTest extends EquityResourceRecoveryTest { String namespace(){return "equity-mysql";} }
class EquityRedisResourceRecoveryTest extends EquityResourceRecoveryTest { String namespace(){return "equity-redis";} }
abstract class EquityResourceRecoveryTest {
    abstract String namespace();
    @TempDir Path root;
    final String image = "sha256:" + "a".repeat(64), artifact = "b".repeat(64), id = "c".repeat(64), token = UUID.randomUUID().toString();
    final String name = "engineering-lab-"+namespace()+"-net-" + token;
    final Fake docker = new Fake();
    Path journal;
    CareerContainerResources resources() { return new EquityContainerResources(image, namespace().equals("equity-redis"), docker::run); }
    void fixture() throws Exception {
        journal = Files.createDirectory(root.resolve(namespace()+"-resources"));
        var intent = WireJson.object().put("version",1).put("profile",namespace()+"-network-v1").put("engineId","engine")
                .put("token",token).put("name",name).put("serviceImage",image).put("runId","run").put("artifactSha256",artifact);
        ContainerJournal.write(journal.resolve("intent.json"),intent);
        ContainerJournal.write(journal.resolve("created.json"), WireJson.object().put("id",id)
                .put("intentSha256",ContainerJournal.hash(ContainerJournal.read(journal.resolve("intent.json")))));
    }
    @ParameterizedTest @ValueSource(strings={"engine","ownership","endpoint","delete","lost-delete","lookup"})
    void resourceFailuresRetainJournalAndLostDeleteResumesByAbsence(String fault) throws Exception {
        fixture(); docker.fault = fault;
        assertThrows(Exception.class, () -> resources().recovery(root,"run",artifact).finish());
        assertFalse(Files.exists(journal.resolve("removed.json")));
        int deletes = docker.deletes;
        if(Set.of("engine","ownership","endpoint","lookup").contains(fault)) assertEquals(0,deletes);
        docker.fault = "";
        resources().recovery(root,"run",artifact).finish();
        assertTrue(Files.isRegularFile(journal.resolve("removed.json"))); assertFalse(docker.present);
        if(fault.equals("lost-delete")) assertEquals(deletes,docker.deletes);
        resources().recovery(root,"run",artifact).finish();
        assertEquals(fault.equals("delete")?2:1,docker.deletes);
    }
    @ParameterizedTest @ValueSource(strings={"unconfirmed","hash","order","link","oversize","deployment"})
    void invalidNetworkRecordsNeverQueryDocker(String fault) throws Exception {
        fixture(); Path created = journal.resolve("created.json");
        switch(fault) {
            case "unconfirmed" -> Files.delete(created);
            case "hash" -> Files.writeString(created,"{\"id\":\""+id+"\",\"intentSha256\":\"bad\"}");
            case "order" -> Files.copy(created,journal.resolve("removed.json"));
            case "link" -> { Files.move(created,journal.resolve("saved")); Files.createSymbolicLink(created,journal.resolve("saved")); }
            case "oversize" -> Files.writeString(created,"x".repeat(4097));
            case "deployment" -> Files.writeString(journal.resolve("intent.json"),Files.readString(journal.resolve("intent.json")).replace(artifact,"d".repeat(64)));
        }
        assertThrows(Exception.class,()->resources().recovery(root,"run",artifact)); assertEquals(0,docker.calls);
    }
    @Test void missingIntentOnlyQualifiesWhenNoOtherResourceRecordsExist() throws Exception {
        journal=Files.createDirectory(root.resolve(namespace()+"-resources"));
        resources().recovery(root,"run",artifact).finish(); assertEquals(0,docker.calls);
        Files.writeString(journal.resolve("created.json"),"{}");
        assertThrows(IOException.class,()->resources().recovery(root,"run",artifact));
    }
    class Fake {
        String fault=""; boolean present=true; int calls,deletes;
        String run(String...args) throws Exception {
            calls++;
            if(args[0].equals("info"))return fault.equals("engine")?"other":"engine";
            if(args[1].equals("ls")) { if(fault.equals("lookup"))throw new IOException("Daemon unavailable");return present?id:""; }
            if(args[1].equals("inspect")) {
                var value=WireJson.object().put("Id",id).put("Name",name).put("Internal",true).put("Attachable",false).put("Driver","bridge");
                value.putObject("Labels").put(DockerContainerEngine.OWNER,fault.equals("ownership")?"other":token)
                        .put(DockerContainerEngine.RUN,"run").put("engineering-lab.resource",namespace()+"-network");
                var endpoints=value.putObject("Containers"); if(fault.equals("endpoint"))endpoints.putObject("unrelated");
                return value.toString();
            }
            if(args[1].equals("rm")) { assertEquals(id,args[2]); deletes++;if(fault.equals("delete"))throw new IOException("Delete rejected");present=false;if(fault.equals("lost-delete"))throw new IOException("Lost response");return id; }
            throw new AssertionError(Arrays.toString(args));
        }
    }
}
