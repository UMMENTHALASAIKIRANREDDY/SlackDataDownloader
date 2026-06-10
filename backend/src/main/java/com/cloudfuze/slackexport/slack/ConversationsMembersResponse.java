package com.cloudfuze.slackexport.slack;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
public class ConversationsMembersResponse extends SlackApiResponse {
    private List<String> members;
}
