package com.RewaBank.accounts.services;

import com.RewaBank.accounts.Utility.AccountType;
import com.RewaBank.accounts.dto.CustomerDto;
import com.RewaBank.accounts.entity.Account;
import java.util.List;

public interface IAccountsService {

public void createAccount(CustomerDto customerDto, AccountType accountType);

public List<Account> getAllAccounts();

public boolean deactivateAccount(Long mobileNumber);

public CustomerDto fetchAccount(String mobileNumber);

public boolean updateAccount(CustomerDto customerDto);

public boolean deleteAccount(String mobileNumber);

boolean updateCommunicationStatus(Long accountNumber);

}
