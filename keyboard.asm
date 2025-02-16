.text
	

loop:
	li $s0, 0xffff0000
	lb $t0, ($s0)
	addi $t5, $s0, 1
forPress:
	beqz $t0, forPressEnd
	
	li $v0, 1
	lb $a0, ($t5)
	syscall
	
	li $v0, 11
	li $a0, 'p'
	syscall
	li $a0, '\n'
	syscall
		
	addi $t0, $t0, -1
	addi $t5, $t5, 1
	
	j forPress
forPressEnd:
	sb $zero ($s0)
	
	li $s1, 0xffff0010
	lb $t1, ($s1)
	addi $t5, $s1, 1
forRelease:
	beqz $t1, forReleaseEnd
	
	li $v0, 1
	lb $a0, ($t5)
	syscall
	
	li $v0, 11
	li $a0, 'r'
	syscall
	li $a0, '\n'
	syscall
		
	addi $t1, $t1, -1
	addi $t5, $t5, 1
	
	j forRelease
forReleaseEnd:
	sb $zero ($s1)
	
	li $v0, 32
	li $a0, 100
	syscall
	j loop
	
	
