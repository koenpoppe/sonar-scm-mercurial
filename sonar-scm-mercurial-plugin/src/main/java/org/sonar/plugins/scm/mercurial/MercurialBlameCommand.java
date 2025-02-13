/*
 * SonarQube :: Plugins :: SCM :: Mercurial
 * Copyright (C) 2014-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.scm.mercurial;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.config.Settings;
import org.sonar.api.utils.command.Command;
import org.sonar.api.utils.command.CommandExecutor;
import org.sonar.api.utils.command.StreamConsumer;
import org.sonar.api.utils.command.StringStreamConsumer;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

public class MercurialBlameCommand extends BlameCommand {

  private static final Logger LOG = Loggers.get(MercurialBlameCommand.class);
  private final CommandExecutor commandExecutor;
  private Settings settings;

  public MercurialBlameCommand(Settings settings) {
    this(CommandExecutor.create(), settings);
  }

  MercurialBlameCommand(CommandExecutor commandExecutor, Settings settings) {
    this.commandExecutor = commandExecutor;
    this.settings = settings;
  }

  @Override
  public void blame(BlameInput input, BlameOutput output) {
    FileSystem fs = input.fileSystem();
    LOG.debug("Working directory: " + fs.baseDir().getAbsolutePath());
    ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 1);
    List<Future<Void>> tasks = new ArrayList<>();
    for (InputFile inputFile : input.filesToBlame()) {
      tasks.add(submitTask(fs, output, executorService, inputFile));
    }

    for (Future<Void> task : tasks) {
      try {
        task.get();
      } catch (ExecutionException e) {
        // Unwrap ExecutionException
        throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new IllegalStateException(e.getCause());
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private Future<Void> submitTask(final FileSystem fs, final BlameOutput result, ExecutorService executorService, final InputFile inputFile) {
    return executorService.submit(new Callable<Void>() {
      @Override
      public Void call() {
        blame(fs, inputFile, result);
        return null;
      }

    });
  }

  private void blame(FileSystem fs, InputFile inputFile, BlameOutput output) {
    String filename = inputFile.relativePath();
    Command cl = createCommandLine(fs.baseDir(), filename);
    MercurialBlameConsumer consumer = new MercurialBlameConsumer(filename);
    StringStreamConsumer stderr = new StringStreamConsumer();

    int exitCode = execute(cl, consumer, stderr);
    if (exitCode != 0) {
      // Ignore the error since it may be caused by uncommited file
      LOG.debug("The mercurial blame command [" + cl.toString() + "] failed: " + stderr.getOutput());
    }
    List<BlameLine> lines = consumer.getLines();
    if (lines.size() == inputFile.lines() - 1) {
      // SONARPLUGINS-3097 Mercurial do not report blame on last empty line
      lines.add(lines.get(lines.size() - 1));
    }
    output.blameResult(inputFile, lines);
  }

  private int execute(Command cl, StreamConsumer consumer, StreamConsumer stderr) {
    LOG.debug("Executing: " + cl);
    return commandExecutor.execute(cl, consumer, stderr, -1);
  }

  private Command createCommandLine(File workingDirectory, String filename) {
    // Determine root repo, i.e., where the .hg directory is
    Path workingPath = workingDirectory.toPath();
    Path filePath = new File(workingDirectory, filename).toPath();
    Path repoRootPath = filePath.getParent();
    while (!repoRootPath.equals(workingPath)) {
      File hgDir = new File(repoRootPath.toFile(), ".hg");
      if (hgDir.exists()) {
        break;
      }
      repoRootPath = repoRootPath.getParent();
    }
    String filePathInRepo = repoRootPath.relativize(filePath).toString();
    
    Command cl = Command.create("hg");
    cl.setDirectory(repoRootPath.toFile());
    cl.addArgument("blame");
    // Hack to support Mercurial prior to 2.1
    if (!settings.getBoolean("sonar.mercurial.considerWhitespaces")) {
      // Ignore whitespaces
      cl.addArgument("-w");
    }
    // Verbose to have user email adress
    cl.addArgument("-v");
    // list the author
    cl.addArgument("--user");
    // list the date
    cl.addArgument("--date");
    // list the global revision number
    cl.addArgument("--changeset");
    // Make filename safe for usage with the "hg" command
    // See Guideline 10 at https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap12.html
    cl.addArgument("--");
    cl.addArgument(filePathInRepo);
    return cl;
  }

}
