package com.yulong.chatagent.errorcode;

import org.springframework.http.HttpStatus;

public interface IErrorCode {

    int code();

    String message();

    HttpStatus httpStatus();
}
