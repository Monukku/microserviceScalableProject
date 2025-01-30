package com.RewaBank.accounts.services.serviceImpl;

import com.RewaBank.accounts.Utility.AccountCategory;
import com.RewaBank.accounts.Utility.AccountStatus;
import com.RewaBank.accounts.Utility.AccountType;
import com.RewaBank.accounts.dto.AccountsMessageDto;
import com.RewaBank.accounts.event.AccountCreatedEvent;
import com.RewaBank.accounts.mapper.AccountsMapper;
import com.RewaBank.accounts.mapper.CustomerMapper;
import com.RewaBank.accounts.constants.AccountsConstants;
import com.RewaBank.accounts.dto.AccountsDto;
import com.RewaBank.accounts.dto.CustomerDto;
import com.RewaBank.accounts.entity.Account;
import com.RewaBank.accounts.entity.Customer;
import com.RewaBank.accounts.exception.CustomerAlreadyExistsException;
import com.RewaBank.accounts.exception.ResourceNotFoundException;
import com.RewaBank.accounts.repository.AccountsRepository;
import com.RewaBank.accounts.repository.CustomerRepository;
import com.RewaBank.accounts.services.IAccountsService;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Random;


@Service
@AllArgsConstructor
public class AccountsServiceImpl implements IAccountsService {
    private static final Logger log= LoggerFactory.getLogger(AccountsServiceImpl.class);
    private AccountsRepository accountsRepository;
    private CustomerRepository customerRepository;
    private StreamBridge streamBridge;
    private static final String TOPIC = "account-topic";
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Transactional
    public void createAccount(CustomerDto customerDto, AccountType accountType) {
        // Map DTO to Customer entity
        Customer customer = CustomerMapper.mapToCustomer(customerDto, new Customer());

        // Check if customer already exists
        Optional<Customer> optionalCustomer = customerRepository.findByMobileNumber(customerDto.getMobileNumber());
        if (optionalCustomer.isPresent()) {
            throw new CustomerAlreadyExistsException("Customer already exists with the given mobile number: " + customerDto.getMobileNumber());
        }

        // Save new customer first
        Customer savedCustomer = customerRepository.save(customer);

        // Create a new account for the saved customer
        Account newAccount = createNewAccount(savedCustomer, accountType);

        // Save the new account to the repository
        Account savedAccount = accountsRepository.save(newAccount);

        AccountCreatedEvent event = new AccountCreatedEvent(savedAccount.getId(), savedAccount.getAccountNumber(), savedAccount.getBalance());
        kafkaTemplate.send(TOPIC, event);

        // Send communication regarding the new account
        sendCommunication(savedAccount, savedCustomer);
    }

    // Helper method to determine account category based on account type
    private AccountCategory determineCategoryForAccount(AccountType accountType) {
        return switch (accountType) {
            case SAVINGS, CHECKING -> AccountCategory.PERSONAL;
            case BUSINESS -> AccountCategory.BUSINESS;
            case JOINT -> AccountCategory.JOINT;
            default -> AccountCategory.DEFAULT; // Default category if not specified
        };
    }

    private Account createNewAccount(Customer customer, AccountType accountType) {
        Account newAccount = new Account();

        // Generate a random account number
        long randomAccountNumber = 1000000000L + new Random().nextInt(900000000);
        newAccount.setAccountNumber(randomAccountNumber);
        newAccount.setBalance(BigDecimal.TEN);
        newAccount.setBranchAddress(AccountsConstants.ADDRESS);

        // Determine account status based on the account type
        switch (accountType) {
            case SAVINGS, CHECKING -> newAccount.setAccountStatus(AccountStatus.ACTIVE);
            case FIXED_DEPOSIT, CREDIT_CARD -> newAccount.setAccountStatus(AccountStatus.INACTIVE);
            case RECURRING_DEPOSIT -> newAccount.setAccountStatus(AccountStatus.PENDING);
            case LOAN -> newAccount.setAccountStatus(AccountStatus.SUSPENDED);
            case BUSINESS, JOINT -> newAccount.setAccountStatus(AccountStatus.ACTIVE); // Assuming active for these types
            default -> throw new IllegalArgumentException("Invalid account type provided");
        }

        // Set account category based on the account type
        AccountCategory category = determineCategoryForAccount(accountType);
        newAccount.setAccountCategory(category);
        newAccount.setAccountType(accountType);
        // Assign the customer to the new account
        newAccount.setCustomer(customer);

        // Add account to customer's accounts list
        customer.addAccount(newAccount);

        return newAccount;
    }

    @Override
    public List<Account> getAllAccounts() {
        return accountsRepository.findAll();
    }

    @Override
    public boolean deactivateAccount(Long accountNumber) {
        // Initialize the deactivation status to false
        boolean isDeactivated = false;

        // Fetch the account by the mobile number, throw exception if not found
        Account account = accountsRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account", "Mobile Number", accountNumber.toString()));

        // Check if the account is already inactive to avoid redundant operations
        if (account.getAccountStatus() == AccountStatus.INACTIVE) {
            throw new IllegalStateException("Account is already inactive");
        }

        // Set the account status to INACTIVE
        account.setAccountStatus(AccountStatus.INACTIVE);

        // Save the updated account back to the repository
        accountsRepository.save(account);

        // Set the deactivation status to true as the operation succeeded
        isDeactivated = true;

        return isDeactivated;
    }

    private void sendCommunication(Account account, Customer customer) {
        var accountsMsgDto = new AccountsMessageDto(account.getAccountNumber(), customer.getName(),
                customer.getEmail(), customer.getMobileNumber());
        log.info("Sending communication request for the details: {}", accountsMsgDto);
        boolean result = streamBridge.send("sendCommunication-out-0", accountsMsgDto);
        log.info("Is the communication request successfully processed? {}", result);
    }

    @Override
    public boolean updateCommunicationStatus(Long accountNumber) {
        boolean isUpdated = false;
        if (accountNumber != null) {
            Account account = accountsRepository.findById(accountNumber)
                    .orElseThrow(() -> new ResourceNotFoundException("Account", "Account number", accountNumber.toString()));
            account.setCommunicationSw(true);
            accountsRepository.save(account);
            isUpdated = true;
        }
        return isUpdated;
    }

    @Override
    public CustomerDto fetchAccount(String mobileNumber) {
        // Fetch the Customer by mobile number, throw exception if not found
        Customer customer = customerRepository.findByMobileNumber(mobileNumber).orElseThrow(
                () -> new ResourceNotFoundException("Customer", "Mobile Number", mobileNumber)
        );

        // Fetch the associated Account by the customer's ID, throw exception if not found
        Account account = accountsRepository.findByCustomerId(customer.getId()).orElseThrow(
                () -> new ResourceNotFoundException("Account", "Customer ID", customer.getId().toString())
        );

        // Map the Customer entity to a CustomerDto
//        CustomerDto customerDto = CustomerMapper.mapToCustomerDto(customer, new CustomerDto());
        CustomerDto customerDto = CustomerMapper.mapToCustomerDto(customer);
        // Map the Account entity to an AccountsDto and set it in the CustomerDto
        customerDto.setAccountsDto(AccountsMapper.mapToAccountsDto(account));

        return customerDto;
    }

    @Override
    public boolean updateAccount(CustomerDto customerDto) {
        boolean isUpdated = false;
        AccountsDto accountsDto = customerDto.getAccountsDto();

        if (accountsDto != null) {
            // Log account number from payload
            System.out.println("Account Number from Payload: " + accountsDto.getAccountNumber());

            // Fetch the account by account number (using a custom query)
            Account existingAccount = accountsRepository.findByAccountNumber(accountsDto.getAccountNumber()).orElseThrow(
                    () -> new ResourceNotFoundException("Account", "AccountNumber", accountsDto.getAccountNumber().toString())
            );

            // Log account number from the database
            System.out.println("Account Number from Database: " + existingAccount.getAccountNumber());

            // Ensure the account numbers match before updating
            if (!existingAccount.getAccountNumber().equals(accountsDto.getAccountNumber())) {
                throw new IllegalArgumentException("Account number mismatch. Cannot update with a different account number.");
            }

            // Update the account entity
            AccountsMapper.mapToAccounts(accountsDto,existingAccount);
            accountsRepository.save(existingAccount);

            // Fetch customer details using the account's customer ID
            Long customerId = existingAccount.getCustomer().getId();
            Customer customer = customerRepository.findById(customerId).orElseThrow(
                    () -> new ResourceNotFoundException("Customer", "CustomerID", customerId.toString())
            );

            // Update customer data and save
            CustomerMapper.mapToCustomer(customerDto, customer);
            customerRepository.save(customer);

            isUpdated = true;  // Mark update as successful
        }

        return isUpdated;
    }


    @Override
    public boolean deleteAccount(String mobileNumber) {
        // Find the customer by mobile number or throw an exception if not found
        Customer customer = customerRepository.findByMobileNumber(mobileNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "Mobile Number", mobileNumber));

        // Find all accounts associated with the customer
        List<Account> accounts = accountsRepository.findAllByCustomerId(customer.getId());

        // Delete all accounts associated with the customer
        if (!accounts.isEmpty()) {
            accountsRepository.deleteAll(accounts);
        } else {
            throw new ResourceNotFoundException("Account", "Customer ID", customer.getId().toString());
        }

        // Delete the customer after accounts are deleted
        customerRepository.deleteById(customer.getId());

        return true;  // Return true indicating successful deletion
    }
}
