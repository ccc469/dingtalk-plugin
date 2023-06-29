package io.jenkins.plugins.model;

import io.jenkins.plugins.enums.BuildStatusEnum;
import io.jenkins.plugins.tools.Utils;

import java.util.Arrays;

import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang.StringUtils;

/**
 * @author liuwei
 */
@Data
@Builder
public class BuildJobModel {

    private String projectName;

    private String projectUrl;

    private String jobName;

    private String jobUrl;

    private BuildStatusEnum statusType;

    private String duration;

    private String executorName;

    private String executorMobile;

    private String content;

    private String buildTime;

    private String gitDiffReviewUrl;

    public String toMarkdown() {
        return Utils.join(
                Arrays.asList(
                        String.format("# [%s](%s)", projectName, projectUrl),
                        "---",
                        String.format("- 时间：%s", buildTime),
                        String.format("- 任务：[%s](%s)", jobName, jobUrl),
                        String.format("- 状态：%s",
                                Utils.dye(
                                        statusType.getLabel(),
                                        statusType.getColor()
                                )
                        ),
                        String.format("- 持续时间：%s", duration),
                        String.format("- 执行人：%s", executorName),
                        String.format("- 预览代码：[点击前往](%s)", gitDiffReviewUrl),
                        content == null ? "" : content
                )
        );
    }
}
