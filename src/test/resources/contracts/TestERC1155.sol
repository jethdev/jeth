// SPDX-License-Identifier: MIT
// Compile: solc --bin --optimize --evm-version berlin TestERC1155.sol
pragma solidity ^0.8.0;

/**
 * Minimal ERC-1155 for integration testing.
 * Supports: balanceOf, uri, isApprovedForAll, setApprovalForAll, safeTransferFrom, mint.
 */
contract TestERC1155 {
    // tokenId => owner => balance
    mapping(uint256 => mapping(address => uint256)) public balanceOf;
    // owner => operator => approved
    mapping(address => mapping(address => bool)) public isApprovedForAll;

    string private _uri;

    event TransferSingle(address indexed operator, address indexed from, address indexed to,
                         uint256 id, uint256 value);
    event ApprovalForAll(address indexed account, address indexed operator, bool approved);

    constructor(string memory uri_) {
        _uri = uri_;
    }

    function uri(uint256) external view returns (string memory) { return _uri; }

    function mint(address to, uint256 id, uint256 amount) external {
        balanceOf[id][to] += amount;
        emit TransferSingle(msg.sender, address(0), to, id, amount);
    }

    function setApprovalForAll(address operator, bool approved) external {
        isApprovedForAll[msg.sender][operator] = approved;
        emit ApprovalForAll(msg.sender, operator, approved);
    }

    function safeTransferFrom(address from, address to, uint256 id, uint256 amount, bytes calldata) external {
        require(from == msg.sender || isApprovedForAll[from][msg.sender], "not authorized");
        require(balanceOf[id][from] >= amount, "insufficient balance");
        balanceOf[id][from] -= amount;
        balanceOf[id][to]   += amount;
        emit TransferSingle(msg.sender, from, to, id, amount);
    }

    function balanceOfBatch(address[] calldata accounts, uint256[] calldata ids)
        external view returns (uint256[] memory) {
        require(accounts.length == ids.length, "length mismatch");
        uint256[] memory bals = new uint256[](accounts.length);
        for (uint256 i = 0; i < accounts.length; i++) {
            bals[i] = balanceOf[ids[i]][accounts[i]];
        }
        return bals;
    }
}
