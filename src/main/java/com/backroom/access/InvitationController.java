package com.backroom.access;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
class InvitationController {
    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/preview")
    AccessDtos.InvitationPreview preview(@Valid @RequestBody AccessDtos.TokenInput input) {
        return invitations.preview(input.token());
    }

    @PostMapping("/accept")
    AccessDtos.OrganizationView accept(@Valid @RequestBody AccessDtos.TokenInput input) {
        return invitations.accept(input.token());
    }
}
