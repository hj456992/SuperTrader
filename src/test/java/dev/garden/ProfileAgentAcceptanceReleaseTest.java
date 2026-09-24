package dev.garden;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic release checks: no Store constructor, database, provider or source connection. */
class ProfileAgentAcceptanceReleaseTest {
    @Test void openingLegacyOrSettledJobDoesNotWriteAnyDocument()throws Exception {
        for(String status:List.of("idle","done","error","cancelled","interrupted")) {
            var repo=new ProfileAgentAcceptanceRuntimeTest.Repo();
            repo.state.withObject("self").set("profile",ProfileAgentAcceptanceTest.fixture().path("legacyProfile"));
            repo.job.put("status",status);
            var before=repo.read();var job=repo.readJob();var books=Store.JSON.valueToTree(repo.books);
            try(var analyzer=new RanchAnalyzer(null,repo,new RanchKnowledge(repo),null)) {
                assertEquals(status,analyzer.status().get("status"));
                assertEquals(before,repo.read());assertEquals(job,repo.readJob());
            }
            assertEquals(0,repo.saves);assertTrue(repo.jobs.isEmpty());
            assertEquals(before,repo.read());assertEquals(books,Store.JSON.valueToTree(repo.books));
        }
    }

    @Test void restartWritesOnlyOneJobAndPreservesResultAlreadySavedBeforeTerminalStatus()throws Exception {
        for(String status:List.of("running","cancelling")) {
            var repo=new ProfileAgentAcceptanceRuntimeTest.Repo();
            var profile=ProfileAgentAcceptanceTest.result("M-self-direct");
            profile.put("runId","qa-saved-window").put("basedOnRevision",6).put("version",1);
            repo.state.withObject("self").set("profile",profile);
            repo.job.put("id","qa-saved-window").put("targetId","self").put("status",status).put("phase","saving");
            var before=repo.read();var books=Store.JSON.valueToTree(repo.books);
            for(int restart=0;restart<2;restart++) {
                try(var analyzer=new RanchAnalyzer(null,repo,new RanchKnowledge(repo),null)) {
                    assertEquals("interrupted",analyzer.status().get("status"));
                    assertEquals("qa-saved-window",analyzer.status().get("id"));
                }
                assertEquals(before,repo.read());assertEquals(0,repo.saves);
                assertEquals(1,repo.jobs.size(),"only first active-job recovery writes; reopening is idempotent");
                assertEquals(books,Store.JSON.valueToTree(repo.books));
            }
        }
    }
}
