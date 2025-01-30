package com.RewaBank.accounts.repository;

import com.RewaBank.accounts.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AccountsRepository extends JpaRepository<Account,Long> {

    public List<Account> findAllByCustomerId(Long customerId);

    public Optional<Account> findByCustomerId(Long customerId);

   public Optional<Account> findByAccountNumber(Long mobileNumber);
}
