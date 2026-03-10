// SPDX-License-Identifier: MIT
// Compile: solc --bin --optimize --evm-version berlin TestCounter.sol
pragma solidity ^0.8.0;

/**
 * Minimal counter contract for integration testing.
 * Useful for StorageLayout, EventDecoder tests that don't need a full token.
 *
 * Storage layout:
 *   slot 0: count (uint256)
 *   slot 1: owner (address, packed with bool: 20+1 = 21 bytes)
 *   slot 1: initialized (bool, byte offset 20)
 *   slot 2: values mapping(address => uint256)
 */
contract TestCounter {
    uint256 public count;
    address public owner;
    bool    public initialized;
    mapping(address => uint256) public values;

    event Incremented(uint256 indexed newCount, address indexed by);
    event ValueSet(address indexed who, uint256 value);

    constructor() {
        owner       = msg.sender;
        initialized = true;
        count       = 0;
    }

    function increment() external {
        count += 1;
        emit Incremented(count, msg.sender);
    }

    function incrementBy(uint256 n) external {
        count += n;
        emit Incremented(count, msg.sender);
    }

    function setValue(uint256 val) external {
        values[msg.sender] = val;
        emit ValueSet(msg.sender, val);
    }

    function reset() external {
        require(msg.sender == owner, "not owner");
        count = 0;
    }
}
