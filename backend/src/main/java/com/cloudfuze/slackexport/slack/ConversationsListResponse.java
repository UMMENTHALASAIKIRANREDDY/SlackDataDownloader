package com.cloudfuze.slackexport.slack;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
public class ConversationsListResponse extends SlackApiResponse {
    private List<JsonNode> channels;
}
