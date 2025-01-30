package com.RewaBank.accounts.services.client;

import com.RewaBank.accounts.dto.LoansDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
@FeignClient(name= "loans", fallback= UserFallBack.class)
public interface UserFeignClient {
        @GetMapping(value = "/api/fetch",consumes = "application/json")
        public ResponseEntity<LoansDto> fetchLoansDetails(@RequestHeader("rewabank-correlation-id") String correlationId, @RequestParam String mobileNumber);

    }

