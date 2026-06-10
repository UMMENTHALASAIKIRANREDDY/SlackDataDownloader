package com.cloudfuze.slackexport.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response for GET /api/export/all-dms-count: DM/group DM counts from both tokens, deduplicated. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllDmsCountResponse {
    /** Unique total (channel ID present in either token, counted once). */
    private int uniqueTotalCount;
    /** One-on-one DMs in the unique set. */
    private int oneOnOneCount;
    /** Group DMs (MPIM) in the unique set. */
    private int groupDmCount;
    /** Count from first token (admin if set, else user). */
    private int fromFirstToken;
    /** Count from second token (user). */
    private int fromSecondToken;
}
