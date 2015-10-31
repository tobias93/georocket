package de.fhg.igd.georocket.commands;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import de.fhg.igd.georocket.GeoRocketCli;
import de.undercouch.citeclip.InputReader;
import de.undercouch.citeclip.OptionParserException;
import de.undercouch.citeclip.UnknownAttributes;
import io.vertx.core.Handler;

/**
 * Displays a command's help
 * @author Michel Kraemer
 */
public class HelpCommand extends AbstractGeoRocketCommand {
  private List<String> commands = new ArrayList<String>();

  /**
   * Sets the commands to display the help for
   * @param commands the commands
   */
  @UnknownAttributes("COMMAND")
  public void setCommands(List<String> commands) {
    this.commands = commands;
  }

  @Override
  public String getUsageName() {
    return "help";
  }

  @Override
  public String getUsageDescription() {
    return "Display a command's help";
  }

  @Override
  public void doRun(String[] remainingArgs, InputReader in, PrintWriter out,
      Handler<Integer> handler) throws OptionParserException, IOException {
    // simply forward commands to GeoRocketCli and append '-h'
    AbstractGeoRocketCommand cmd = new GeoRocketCli();
    String[] args = commands.toArray(new String[commands.size() + 1]);
    args[args.length - 1] = "-h";
    cmd.setEndHandler(handler);
    cmd.run(args, in, out);
  }
}