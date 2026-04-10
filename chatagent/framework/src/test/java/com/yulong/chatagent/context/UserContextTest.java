package com.yulong.chatagent.context;

import com.yulong.chatagent.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserContextTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        UserContext.clear();
    }

    @Test
    void get_returnsNull_whenNotSet() {
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void set_andGet_returnsSameUser() {
        LoginUser user = LoginUser.builder().userId("u1").username("alice").build();
        UserContext.set(user);

        assertThat(UserContext.get()).isSameAs(user);
        assertThat(UserContext.get().getUserId()).isEqualTo("u1");
    }

    @Test
    void clear_removesUser() {
        UserContext.set(LoginUser.builder().userId("u2").build());
        UserContext.clear();

        assertThat(UserContext.get()).isNull();
    }

    @Test
    void requireUser_returnsUser_whenPresent() {
        LoginUser user = LoginUser.builder().userId("u3").build();
        UserContext.set(user);

        assertThat(UserContext.requireUser()).isSameAs(user);
    }

    @Test
    void requireUser_throwsBizException_whenAbsent() {
        assertThatThrownBy(UserContext::requireUser)
                .isInstanceOf(BizException.class)
                .hasMessage("User not authenticated");
    }

    @Test
    void threadIsolation_differentThreadsSeeDifferentUsers() throws Exception {
        LoginUser mainUser = LoginUser.builder().userId("main").build();
        UserContext.set(mainUser);

        Thread t = new Thread(() -> {
            assertThat(UserContext.get()).isNull();
            LoginUser otherUser = LoginUser.builder().userId("other").build();
            UserContext.set(otherUser);
            assertThat(UserContext.get().getUserId()).isEqualTo("other");
        });
        t.start();
        t.join();

        assertThat(UserContext.get().getUserId()).isEqualTo("main");
    }
}
