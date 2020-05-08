/*
 * Sonar C++ Plugin (Community)
 * Copyright (C) 2010-2020 SonarOpenCommunity
 * http://github.com/SonarOpenCommunity/sonar-cxx
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
package org.sonar.cxx.sensors.pclint;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.stream.XMLStreamException;
import org.codehaus.staxmate.in.SMHierarchicCursor;
import org.codehaus.staxmate.in.SMInputCursor;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.cxx.sensors.utils.CxxIssuesReportSensor;
import org.sonar.cxx.sensors.utils.CxxUtils;
import org.sonar.cxx.sensors.utils.EmptyReportException;
import org.sonar.cxx.sensors.utils.StaxParser;
import org.sonar.cxx.utils.CxxReportIssue;
import org.sonar.cxx.utils.CxxReportLocation;

/**
 * PC-lint is an equivalent to pmd but for C++ The first version of the tool was release 1985 and the tool analyzes
 * C/C++ source code from many compiler vendors. PC-lint is the version for Windows and FlexLint for Unix, VMS, OS-9,
 * etc See also: http://www.gimpel.com/html/index.htm
 *
 * @author Bert
 */
public class CxxPCLintSensor extends CxxIssuesReportSensor {

  public static final String REPORT_PATH_KEY = "sonar.cxx.pclint.reportPath";
  public static final Pattern MISRA_RULE_PATTERN = Pattern.compile(
    // Rule nn.nn -or- Rule nn-nn-nn
    "Rule\\x20(\\d{1,2}.\\d{1,2}|\\d{1,2}-\\d{1,2}-\\d{1,2})(,|\\])");
  private static final Logger LOG = Loggers.get(CxxPCLintSensor.class);

  private static final String SUPPLEMENTAL_TYPE_ISSUE = "supplemental";

  private static final String PREFIX_DURING_SPECIFIC_WALK_MSG = "during specific walk";

  private static final Pattern SUPPLEMENTAL_MSG_PATTERN
                                 = Pattern.compile(PREFIX_DURING_SPECIFIC_WALK_MSG + "\\s+(.+):(\\d+):(\\d+)\\s+.+");

  public static List<PropertyDefinition> properties() {
    return Collections.unmodifiableList(Arrays.asList(
      PropertyDefinition.builder(REPORT_PATH_KEY)
        .name("PC-lint XML report(s)")
        .description(
          "Path to <a href='http://www.gimpel.com/html/pcl.htm'>PC-lint</a> XML reports(s), relative to projects"
            + "  root." + USE_ANT_STYLE_WILDCARDS)
        .category("CXX External Analyzers")
        .subCategory("PC-lint")
        .onQualifiers(Qualifiers.PROJECT)
        .multiValues(true)
        .build()
    ));
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("CXX PC-lint report import")
      .onlyOnLanguage("cxx")
      .createIssuesForRuleRepository(getRuleRepositoryKey())
      .onlyWhenConfiguration(conf -> conf.hasKey(getReportPathKey()));
  }

  @Override
  protected void processReport(File report)
    throws javax.xml.stream.XMLStreamException {
    LOG.debug("Processing 'PC-Lint' format");

    var parser = new StaxParser(new StaxParser.XmlStreamHandler() {
      /**
       * {@inheritDoc}
       */
      @Override
      public void stream(SMHierarchicCursor rootCursor) throws XMLStreamException {
        try {
          rootCursor.advance();
        } catch (com.ctc.wstx.exc.WstxEOFException eofExc) {
          throw new EmptyReportException("Cannot read PClint report", eofExc);
        }

        // collect the "supplemental" messages generated by pc-lint,
        // which are very helpful to track why/when the original issue is found.
        // The "supplemental" messages will be right after the original issue.
        // NOTE: Require the "type" attribute in the report.
        CxxReportIssue currentIssue = null;

        SMInputCursor errorCursor = rootCursor.childElementCursor("issue");
        try {
          while (errorCursor.getNext() != null) {
            String file = errorCursor.getAttrValue("file");
            String line = errorCursor.getAttrValue("line");
            String id = errorCursor.getAttrValue("number");
            String msg = errorCursor.getAttrValue("desc");
            String type = errorCursor.getAttrValue("type");

            // handle the case when supplemental message has no file and line
            // eg, issue 894.
            if (SUPPLEMENTAL_TYPE_ISSUE.equals(type) && currentIssue != null) {
              addSecondaryLocationsToCurrentIssue(currentIssue, file, line, msg);
              continue;
            }

            if (isInputValid(file, line, id, msg)) {
              if (msg.contains("MISRA")) {
                //remap MISRA IDs. Only Unique rules for MISRA C 2004 and MISRA C/C++ 2008
                // have been created in the rule repository
                if (msg.contains("MISRA 2004") || msg.contains("MISRA 2008")
                      || msg.contains("MISRA C++ 2008") || msg.contains("MISRA C++ Rule")) {
                  id = mapMisraRulesToUniqueSonarRules(msg, false);
                } else if (msg.contains("MISRA 2012 Rule")) {
                  id = mapMisraRulesToUniqueSonarRules(msg, true);
                }
              }

              if (currentIssue != null) {
                saveUniqueViolation(currentIssue);
              }

              currentIssue = new CxxReportIssue(id, file, line, msg);
            } else {
              LOG.warn("PC-lint warning ignored: {}", msg);
              LOG.debug("File: {}, Line: {}, ID: {}, msg: {}", file, line, id, msg);
            }
          }

          if (currentIssue != null) {
            saveUniqueViolation(currentIssue);
          }
        } catch (com.ctc.wstx.exc.WstxUnexpectedCharException
                   | com.ctc.wstx.exc.WstxEOFException
                   | com.ctc.wstx.exc.WstxIOException e) {
          LOG.error("Ignore XML error from PC-lint '{}'", CxxUtils.getStackTrace(e));
        }
      }

      private void addSecondaryLocationsToCurrentIssue(@Nonnull CxxReportIssue currentIssue,
                                                       String file,
                                                       String line,
                                                       String msg) {
        if (currentIssue.getLocations().isEmpty()) {
          LOG.error("The issue of {} must have the primary location. Skip adding more locations",
                    currentIssue.toString());
          return;
        }

        if (file != null && file.isEmpty() && msg != null) {
          Matcher matcher = SUPPLEMENTAL_MSG_PATTERN.matcher(msg);

          if (matcher.matches()) {
            file = matcher.group(1);
            line = matcher.group(2);
          }
        }

        if (file == null || file.isEmpty() || line == null || line.isEmpty()) {
          return;
        }

        // Due to SONAR-9929, even the API supports the extra/flow in different file,
        // the UI is not ready. For this case, use the parent issue's file and line for now.
        CxxReportLocation primaryLocation = currentIssue.getLocations().get(0);
        if (!primaryLocation.getFile().equals(file)) {
          if (!msg.startsWith(PREFIX_DURING_SPECIFIC_WALK_MSG)) {
            msg = String.format("%s %s:%s %s", PREFIX_DURING_SPECIFIC_WALK_MSG, file, line, msg);
          }

          file = primaryLocation.getFile();
          line = primaryLocation.getLine();
        }

        currentIssue.addFlowElement(file, line, msg);
      }

      private boolean isInputValid(@Nullable String file, @Nullable String line,
                                   @Nullable String id, @Nullable String msg) {
        try {
          if (file == null || file.isEmpty() || (Integer.parseInt(line) == 0)) {
            // issue for project or file level
            return id != null && !id.isEmpty() && msg != null && !msg.isEmpty();
          }
          return !file.isEmpty() && id != null && !id.isEmpty() && msg != null && !msg.isEmpty();
        } catch (java.lang.NumberFormatException e) {
          LOG.error("Ignore number error from PC-lint report '{}'", CxxUtils.getStackTrace(e));
        }
        return false;
      }

      /**
       * Concatenate M with the MISRA rule number to get the new rule id to save the violation to.
       */
      private String mapMisraRulesToUniqueSonarRules(String msg, boolean isMisra2012) {
        Matcher matcher = MISRA_RULE_PATTERN.matcher(msg);
        if (matcher.find()) {
          String misraRule = matcher.group(1);
          String newKey;
          if (isMisra2012) {
            newKey = "M2012-" + misraRule;
          } else {
            newKey = "M" + misraRule;
          }

          LOG.debug("Remap MISRA rule {} to key {}", misraRule, newKey);
          return newKey;
        }
        return "";
      }
    });

    parser.parse(report);
  }

  @Override
  protected String getReportPathKey() {
    return REPORT_PATH_KEY;
  }

  @Override
  protected String getRuleRepositoryKey() {
    return CxxPCLintRuleRepository.KEY;
  }

}
