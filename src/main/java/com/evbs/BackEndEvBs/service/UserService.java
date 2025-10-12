package com.evbs.BackEndEvBs.service;


import com.evbs.BackEndEvBs.entity.User;
import com.evbs.BackEndEvBs.exception.exceptions.AuthenticationException;
import com.evbs.BackEndEvBs.model.request.CreateUserRequest;
import com.evbs.BackEndEvBs.model.request.UpdateUserRequest;

import com.evbs.BackEndEvBs.model.response.UserResponse;
import com.evbs.BackEndEvBs.repository.UserRepository;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;



import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {

    @Autowired
    UserRepository userRepository;

    @Autowired
    ModelMapper modelMapper;

    @Autowired
    AuthenticationService authenticationService;

    @Autowired
    PasswordEncoder passwordEncoder;

    /**
     * Tạo user mới
     */
    public UserResponse createUser(CreateUserRequest request) {
        // Kiểm tra email đã tồn tại
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AuthenticationException("Email already exists!");
        }

        // Kiểm tra phone number đã tồn tại
        if (request.getPhoneNumber() != null && userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new AuthenticationException("Phone number already exists!");
        }

        // Tạo user mới
        User user = new User();
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());
        user.setStatus(request.getStatus());

        User savedUser = userRepository.save(user);
        return modelMapper.map(savedUser, UserResponse.class);
    }

    /**
     * Lấy thông tin user theo ID
     */
    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AuthenticationException("User not found with id: " + id));
        return modelMapper.map(user, UserResponse.class);
    }

    /**
     * Cập nhật thông tin user
     */
    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        // Kiểm tra user hiện tại không được cập nhật chính mình
        User currentUser = authenticationService.getCurrentUser();
        if (currentUser.getId().equals(id)) {
            throw new AuthenticationException("You cannot update your own account");
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new AuthenticationException("User not found with id: " + id));

        // Cập nhật các field nếu có giá trị mới
        if (request.getFullName() != null && !request.getFullName().trim().isEmpty()) {
            user.setFullName(request.getFullName());
        }

        if (request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            // Kiểm tra email mới có trùng với user khác không
            if (!user.getEmail().equals(request.getEmail()) && userRepository.existsByEmail(request.getEmail())) {
                throw new AuthenticationException("Email already exists!");
            }
            user.setEmail(request.getEmail());
        }

        if (request.getPhoneNumber() != null && !request.getPhoneNumber().trim().isEmpty()) {
            // Kiểm tra phone number mới có trùng với user khác không
            if (!user.getPhoneNumber().equals(request.getPhoneNumber()) && userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
                throw new AuthenticationException("Phone number already exists!");
            }
            user.setPhoneNumber(request.getPhoneNumber());
        }

        if (request.getRole() != null) {
            user.setRole(request.getRole());
        }

        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }

        User updatedUser = userRepository.save(user);
        return modelMapper.map(updatedUser, UserResponse.class);
    }

    /**
     * Xóa user (soft delete bằng cách đổi status)
     */
    public void deleteUser(Long id) {
        // Kiểm tra user hiện tại không được xóa chính mình
        User currentUser = authenticationService.getCurrentUser();
        if (currentUser.getId().equals(id)) {
            throw new AuthenticationException("You cannot delete your own account");
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new AuthenticationException("User not found with id: " + id));
        
        user.setStatus(User.Status.INACTIVE); // Có thể tạo thêm DELETED status nếu cần
        userRepository.save(user);
    }

    /**
     * Lấy danh sách tất cả user đơn giản 
     */
    public List<UserResponse> getAllUsersSimple() {
        List<User> users = userRepository.findAll();
        return users.stream()
                .map(user -> modelMapper.map(user, UserResponse.class))
                .collect(Collectors.toList());
    }
}