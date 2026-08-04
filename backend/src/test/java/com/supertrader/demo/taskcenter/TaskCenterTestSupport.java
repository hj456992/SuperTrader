package com.supertrader.demo.taskcenter;

import com.supertrader.demo.strategy.StrategyStore;
import com.supertrader.demo.team.TeamStore;
import com.supertrader.demo.workspace.WorkspaceStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds a complete Module 9 store stack against scratch temp files so unit
 * tests never touch the real rebuild/.run/ or the real datasets. The datasets
 * are copied from the committed rebuild/datasets fixtures (read-only test
 * inputs, clearly labeled acceptance samples) plus optional synthetic
 * fixtures (stale / small / mismatched) for the fail-closed tests.
 */
final class TaskCenterTestSupport {

    private TaskCenterTestSupport() {}

    record Stack(Path tmpDir, Path datasetsDir, WorkspaceStore workspaces,
                 TeamStore teams, StrategyStore strategies, DatasetCatalog catalog,
                 StrategySpecValidator validator, BacktestRunnerService runner,
                 TaskCenterStore store, KnowledgeService knowledge) {}

    static Stack build() throws IOException {
        Path tmp = Files.createTempDirectory("simnow-taskcenter-unit-");
        Path wsFile = tmp.resolve("workspaces.json");
        Path teamsFile = tmp.resolve("teams.json");
        Path strategiesFile = tmp.resolve("strategies.json");
        Path taskCenterFile = tmp.resolve("task-center.json");
        Path datasetsDir = tmp.resolve("datasets");
        Files.createDirectories(datasetsDir);

        WorkspaceStore workspaces = new WorkspaceStore(wsFile.toString());
        TeamStore teams = new TeamStore(teamsFile.toString(), workspaces);
        StrategyStore strategies = new StrategyStore(strategiesFile.toString(),
                workspaces, teams);
        DatasetCatalog catalog = new DatasetCatalog(datasetsDir.toString());
        StrategySpecValidator validator = new StrategySpecValidator(catalog);
        BacktestRunnerService runner = new BacktestRunnerService(catalog);
        TaskCenterStore store = new TaskCenterStore(taskCenterFile.toString(),
                workspaces, teams, strategies, validator, catalog, runner);
        // The knowledge base is the committed rebuild/knowledge/ (read-only).
        Path knowledgeDir = Path.of(System.getProperty("user.dir"))
                .getParent().resolve("knowledge");
        KnowledgeService knowledge = new KnowledgeService(knowledgeDir.toString());
        return new Stack(tmp, datasetsDir, workspaces, teams, strategies, catalog,
                validator, runner, store, knowledge);
    }

    /** Copy the committed acceptance datasets into the scratch datasets dir. */
    static void copyAcceptanceDatasets(Stack stack) throws IOException {
        Path real = Path.of(System.getProperty("user.dir"))
                .getParent().resolve("datasets");
        try (var stream = Files.list(real)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                Files.copy(p, stack.datasetsDir().resolve(p.getFileName()));
            }
        }
    }
}
