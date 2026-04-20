package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.com.example.demo.dto.OrderDTO;

public interface OrderRepository extends JpaRepository<OrderDTO,Integer> {

    List<OrderDTO> findByCategory(String category);


}