package com.cloudfuze.slackexport.slack;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class FilesInfoResponse extends SlackApiResponse {
    private JsonNode file;
}
