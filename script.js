// get current year
const yearElement = document.getElementById('copyright-text');
if (yearElement) {
    yearElement.innerHTML = `&copy; ${new Date().getFullYear()}`;
}

// donkey rub counter
window.addEventListener('DOMContentLoaded', () => {
    const hitbox = document.getElementById('hitbox');
    const counterDisplay = document.getElementById('counter');
    let rubCount = 0;
    let confettiPower = 1;

    if (hitbox && counterDisplay) {
        hitbox.addEventListener('mouseenter', () => {
            rubCount++;
            counterDisplay.textContent = rubCount;

            if (rubCount > 0 && rubCount % 25 === 0) {
                confetti({
                    particleCount: 500 * confettiPower,
                    spread: 160 * confettiPower * 0.2,
                    origin: { y: 0.77, x: 0.46 },
                    zIndex: 999 // ensures it appears above all elements
                });

                confettiPower++;
            }
        });
    }

    // "Press Me" button logic
    const pressMeBtn = document.getElementById('press-me-btn');
    const gameUI = document.querySelector('.game-ui'); // select the score container

    if (pressMeBtn) {
        pressMeBtn.addEventListener('click', (e) => {
            e.preventDefault(); // prevents page jump
            
            // reveal game UI
            if (gameUI) {
                gameUI.style.display = 'flex';
            }

            drawGoBoard();
        })
    }
});

// Global Game State (Accessible by all functions)
let currentPlayer = 'black';
let blackCaptures = 0;
let whiteCaptures = 0;
let boardState = [];
let history = [];
const size = 9;
const padding = 25;

// draw Go board
function drawGoBoard() {
    const container = document.getElementById('game-area');
    container.innerHTML = ''; 

    const canvas = document.createElement('canvas');
    canvas.width = 450; 
    canvas.height = 450;
    const ctx = canvas.getContext('2d');
    container.appendChild(canvas);

    const spacing = (canvas.width - (padding * 2)) / (size - 1);
    
    // Reset state for a new game
    currentPlayer = 'black';
    blackCaptures = 0;
    whiteCaptures = 0;
    boardState = Array(size).fill(null).map(() => Array(size).fill(null));
    history = [];

    // Initial draw
    renderAll(ctx, boardState, size, padding, spacing);

    canvas.addEventListener('click', (event) => {
        const rect = canvas.getBoundingClientRect();
        const mouseX = event.clientX - rect.left;
        const mouseY = event.clientY - rect.top;
        const col = Math.round((mouseX - padding) / spacing);
        const row = Math.round((mouseY - padding) / spacing);

        if (col >= 0 && col < size && row >= 0 && row < size) {
            if (boardState[row][col] === null) {
                // Save history before move
                history.push(JSON.parse(JSON.stringify(boardState)));
                
                boardState[row][col] = currentPlayer;
                const opponent = (currentPlayer === 'black') ? 'white' : 'black';
                let capturedAny = false;

                getNeighbors(row, col, size).forEach(nb => {
                    if (boardState[nb.r][nb.c] === opponent) {
                        const result = checkGroup(nb.r, nb.c, boardState, size);
                        if (result.liberties === 0) {
                            if (currentPlayer === 'black') blackCaptures += result.group.length;
                            else whiteCaptures += result.group.length;

                            result.group.forEach(s => boardState[s.r][s.c] = null);
                            capturedAny = true;
                        }
                    }
                });

                // Suicide check
                const ownGroup = checkGroup(row, col, boardState, size);
                if (!capturedAny && ownGroup.liberties === 0) {
                    boardState[row][col] = null; 
                    history.pop(); // Remove from history since move failed
                    console.log("move blocked: suicide is not allowed!");
                    return;
                }

                renderAll(ctx, boardState, size, padding, spacing);
                currentPlayer = opponent;
            }
        }
    });

    canvas.addEventListener('mousemove', (event) => {
        const rect = canvas.getBoundingClientRect();
        const mouseX = event.clientX - rect.left;
        const mouseY = event.clientY - rect.top;
        const col = Math.round((mouseX - padding) / spacing);
        const row = Math.round((mouseY - padding) / spacing);

        if (col >= 0 && col < size && row >= 0 && row < size && !boardState[row][col]) {
            renderAll(ctx, boardState, size, padding, spacing);
            ctx.globalAlpha = 0.4;
            placeStone(ctx, col, row, padding, spacing, currentPlayer);
            ctx.globalAlpha = 1.0;
        } else {
            renderAll(ctx, boardState, size, padding, spacing);
        }
    });

}

function renderAll(ctx, boardState, size, padding, spacing) {
    const w = ctx.canvas.width;
    const h = ctx.canvas.height;

    ctx.fillStyle = "#deb887";
    ctx.fillRect(0, 0, w, h);

    ctx.beginPath();
    ctx.strokeStyle = "#000";
    for (let i = 0; i < size; i++) {
        ctx.moveTo(padding + i * spacing, padding);
        ctx.lineTo(padding + i * spacing, h - padding);
        ctx.moveTo(padding, padding + i * spacing);
        ctx.lineTo(w - padding, padding + i * spacing);
    }
    ctx.stroke();

    // Update Scores
    const bScore = document.getElementById('score-black');
    const wScore = document.getElementById('score-white');
    if(bScore) bScore.innerText = `Black Captures: ${blackCaptures}`;
    if(wScore) wScore.innerText = `White Captures: ${whiteCaptures}`;

    const dots = [2, 4, 6];
    dots.forEach(r => dots.forEach(c => {
        ctx.beginPath();
        ctx.arc(padding + r * spacing, padding + c * spacing, 4, 0, Math.PI * 2);
        ctx.fillStyle = "#000";
        ctx.fill();
    }));

    for (let r = 0; r < size; r++) {
        for (let c = 0; c < size; c++) {
            if (boardState[r][c]) {
                placeStone(ctx, c, r, padding, spacing, boardState[r][c]);
            }
        }
    }
}

function placeStone(ctx, col, row, padding, spacing, color) {
    ctx.beginPath();
    ctx.arc(padding + col * spacing, padding + row * spacing, spacing / 2.2, 0, Math.PI * 2);
    ctx.fillStyle = color;
    ctx.fill();
    ctx.strokeStyle = "#333";
    ctx.lineWidth = 1;
    ctx.stroke();
}

function getNeighbors(r, c, size) {
    const n = [];
    if (r > 0) n.push({r: r - 1, c});
    if (r < size - 1) n.push({r: r + 1, c});
    if (c > 0) n.push({r, c: c - 1});
    if (c < size - 1) n.push({r, c: c + 1});
    return n;
}

function checkGroup(row, col, boardState, size) {
    const color = boardState[row][col];
    const group = [];
    const stack = [{r: row, c: col}];
    const visited = new Set();
    let liberties = 0;

    while (stack.length > 0) {
        const curr = stack.pop();
        const key = `${curr.r}-${curr.c}`;
        if (visited.has(key)) continue;

        visited.add(key);
        group.push(curr);

        getNeighbors(curr.r, curr.c, size).forEach(nb => {
            if (boardState[nb.r][nb.c] === null) {
                liberties++;
            } else if (boardState[nb.r][nb.c] === color) {
                stack.push(nb);
            }
        });
    }
    return {group, liberties};
}