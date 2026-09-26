package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AddMemberRequest;
import com.webhook.platform.api.dto.AuthResponse;
import com.webhook.platform.api.dto.CurrentUserResponse;
import com.webhook.platform.api.dto.LoginRequest;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
public class MembershipRbacTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Test1234!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Test
    public void aDeveloperCannotAddMembers() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthResponse owner = read(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RegisterRequest.builder()
                                .email("rbac-owner-" + suffix + "@example.com")
                                .password(PASSWORD)
                                .organizationName("Rbac Org " + suffix)
                                .build())))
                .andExpect(status().isCreated())
                .andReturn(), AuthResponse.class);
        UUID orgId = read(mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + owner.getAccessToken()))
                .andExpect(status().isOk())
                .andReturn(), CurrentUserResponse.class).getOrganization().getId();

        // Seeded directly so the developer's only organization, and so its token's, is the owner's.
        String developerEmail = "rbac-developer-" + suffix + "@example.com";
        UUID developerId = userRepository.saveAndFlush(User.builder()
                .email(developerEmail)
                .passwordHash(new BCryptPasswordEncoder().encode(PASSWORD))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build()).getId();
        membershipRepository.saveAndFlush(Membership.builder()
                .userId(developerId)
                .organizationId(orgId)
                .role(MembershipRole.DEVELOPER)
                .status(MembershipStatus.ACTIVE)
                .build());
        AuthResponse developer = read(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(LoginRequest.builder()
                                .email(developerEmail)
                                .password(PASSWORD)
                                .build())))
                .andExpect(status().isOk())
                .andReturn(), AuthResponse.class);

        String invitee = "rbac-viewer-" + suffix + "@example.com";
        mockMvc.perform(post("/api/v1/orgs/" + orgId + "/members")
                        .header("Authorization", "Bearer " + developer.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AddMemberRequest.builder()
                                .email(invitee)
                                .role(MembershipRole.VIEWER)
                                .build())))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findByEmail(invitee)).isEmpty();
    }

    private <T> T read(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type);
    }
}
