package com.example.expenseapp.service;

import com.example.expenseapp.audit.AuditLogService;
import com.example.expenseapp.entity.ApprovalAction;
import com.example.expenseapp.entity.Expense;
import com.example.expenseapp.entity.ExpenseStatus;
import com.example.expenseapp.exception.ExpenseNotFoundException;
import com.example.expenseapp.exception.InvalidExpenseStateException;
import com.example.expenseapp.exception.OptimisticLockException;
import com.example.expenseapp.mapper.ApprovalHistoryMapper;
import com.example.expenseapp.security.LoginUser;
import com.example.expenseapp.service.dto.ExpenseSaveCommand;
import com.example.expenseapp.support.AbstractMySqlIntegrationTest;
import com.example.expenseapp.support.AuditLogAssertions;
import com.example.expenseapp.support.ExpenseTestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpenseWithdrawalServiceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ExpenseService expenseService;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalHistoryMapper approvalHistoryMapper;

    @Test
    void applicantCanWithdrawPendingExpenseAndRecordsHistory() {
        LoginUser yamada = loginAs("yamada");
        Expense submitted = createPendingExpense(yamada, "取り下げ成功");

        Expense withdrawn = expenseService.withdraw(
                submitted.getExpenseId(), yamada, submitted.getVersion(), "誤って申請したため");

        assertThat(withdrawn.getStatus()).isEqualTo(ExpenseStatus.WITHDRAWN);
        assertThat(withdrawn.getVersion()).isEqualTo(submitted.getVersion() + 1);

        var history = approvalHistoryMapper.findByExpenseId(submitted.getExpenseId()).getLast();
        assertThat(history.getAction()).isEqualTo(ApprovalAction.WITHDRAW);
        assertThat(history.getApproverUserId()).isEqualTo(yamada.getUserId());
        assertThat(history.getComment()).isEqualTo("誤って申請したため");

        assertThat(AuditLogAssertions.countByAction(
                jdbcTemplate,
                AuditLogService.ACTION_EXPENSE_WITHDRAW,
                submitted.getExpenseId())).isEqualTo(1);
    }

    @Test
    void withdrawRequiresReason() {
        LoginUser yamada = loginAs("yamada");
        Expense submitted = createPendingExpense(yamada, "理由必須");

        assertThatThrownBy(() -> expenseService.withdraw(
                submitted.getExpenseId(), yamada, submitted.getVersion(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("取り下げ理由を入力してください。");
    }

    @Test
    void otherUserCannotWithdrawExpense() {
        LoginUser yamada = loginAs("yamada");
        Expense submitted = createPendingExpense(yamada, "他人の申請");

        LoginUser takahashi = loginAs("takahashi");

        assertThatThrownBy(() -> expenseService.withdraw(
                submitted.getExpenseId(), takahashi, submitted.getVersion(), "理由"))
                .isInstanceOf(ExpenseNotFoundException.class);
    }

    @Test
    void approverCannotWithdrawExpense() {
        LoginUser yamada = loginAs("yamada");
        Expense submitted = createPendingExpense(yamada, "承認者の操作");

        LoginUser sato = loginAs("sato");

        assertThatThrownBy(() -> expenseService.withdraw(
                submitted.getExpenseId(), sato, submitted.getVersion(), "理由"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staleVersionCannotWithdrawExpense() {
        LoginUser yamada = loginAs("yamada");
        Expense submitted = createPendingExpense(yamada, "古いバージョン");

        assertThatThrownBy(() -> expenseService.withdraw(
                submitted.getExpenseId(), yamada, submitted.getVersion() - 1, "理由"))
                .isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void onlyPendingExpenseCanBeWithdrawn() {
        LoginUser yamada = loginAs("yamada");

        Expense draft = expenseService.createDraft(
                yamada, ExpenseTestDataFactory.sampleCommand("下書き"));
        assertCannotWithdraw(yamada, draft);

        Expense rejectedPending = createPendingExpense(yamada, "差戻し済み");
        LoginUser sato = loginAs("sato");
        Expense rejected = approvalService.reject(
                rejectedPending.getExpenseId(), sato, rejectedPending.getVersion(), "差戻し");
        assertCannotWithdraw(yamada, rejected);

        Expense approvedPending = createPendingExpense(yamada, "承認済み");
        Expense firstApproved = approvalService.approve(
                approvedPending.getExpenseId(), sato, approvedPending.getVersion(), "一次承認");
        LoginUser suzuki = loginAs("suzuki");
        Expense approved = approvalService.approve(
                firstApproved.getExpenseId(), suzuki, firstApproved.getVersion(), "最終承認");
        assertCannotWithdraw(yamada, approved);

        LoginUser owner = loginAs("yamada");
        Expense pending = createPendingExpense(owner, "取り下げ済み");
        Expense withdrawn = expenseService.withdraw(
                pending.getExpenseId(), owner, pending.getVersion(), "取り下げ");
        assertCannotWithdraw(owner, withdrawn);
    }

    @Test
    void withdrawnExpenseIsExcludedFromApprovalAndCannotBeChangedOrProcessed() {
        LoginUser yamada = loginAs("yamada");
        Expense submitted = createPendingExpense(yamada, "取り下げ後の操作");
        Expense withdrawn = expenseService.withdraw(
                submitted.getExpenseId(), yamada, submitted.getVersion(), "誤申請");

        LoginUser sato = loginAs("sato");

        assertThat(approvalService.findPendingExpenses(sato, null))
                .extracting("expenseId")
                .doesNotContain(withdrawn.getExpenseId());

        ExpenseSaveCommand update = ExpenseTestDataFactory.sampleCommand("編集");
        update.setVersion(withdrawn.getVersion());

        assertThatThrownBy(() -> expenseService.updateDraft(
                withdrawn.getExpenseId(), yamada, update))
                .isInstanceOf(InvalidExpenseStateException.class);

        assertThatThrownBy(() -> expenseService.deleteDraft(
                withdrawn.getExpenseId(), yamada, withdrawn.getVersion()))
                .isInstanceOf(InvalidExpenseStateException.class);

        assertThatThrownBy(() -> expenseService.submit(
                withdrawn.getExpenseId(), yamada, withdrawn.getVersion()))
                .isInstanceOf(InvalidExpenseStateException.class);

        assertThatThrownBy(() -> approvalService.approve(
                withdrawn.getExpenseId(), sato, withdrawn.getVersion(), ""))
                .isInstanceOf(ExpenseNotFoundException.class);

        assertThatThrownBy(() -> approvalService.reject(
                withdrawn.getExpenseId(), sato, withdrawn.getVersion(), "差戻し理由"))
                .isInstanceOf(ExpenseNotFoundException.class);
    }

    private Expense createPendingExpense(LoginUser owner, String title) {
        Expense draft = expenseService.createDraft(
                owner, ExpenseTestDataFactory.sampleCommand(title));
        return submitWithReceipt(owner, draft);
    }

    private void assertCannotWithdraw(LoginUser owner, Expense expense) {
        LoginUser currentUser = loginAs(owner.getUsername());

        assertThatThrownBy(() -> expenseService.withdraw(
                expense.getExpenseId(),
                currentUser,
                expense.getVersion(),
                "取り下げ理由"))
                .isInstanceOf(InvalidExpenseStateException.class);
    }
}