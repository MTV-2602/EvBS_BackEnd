package com.evbs.BackEndEvBs.model.request;

import com.evbs.BackEndEvBs.entity.SupportTicket;
import lombok.Data;

@Data
public class SupportTicketUpdateRequest {

    private Long stationId;

    private String subject;

    private String description;

    private SupportTicket.Status status;
}