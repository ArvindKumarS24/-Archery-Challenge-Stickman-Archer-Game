// Archery Challenge - HTML5 Canvas version
// Drop index.html, style.css, game.js in a folder. Open index.html or host on Netlify.

(() => {
  const canvas = document.getElementById('game');
  const ctx = canvas.getContext('2d', { alpha: false });

  // UI elements
  const startBtn = document.getElementById('start');
  const pauseBtn = document.getElementById('pause');
  const restartBtn = document.getElementById('restart');
  const diffSel = document.getElementById('difficulty');
  const scoreEl = document.getElementById('score');
  const highEl = document.getElementById('highscore');
  const arrowsEl = document.getElementById('arrows');
  const timeEl = document.getElementById('time');

  // device pixel ratio for crisp canvas
  function resizeCanvas() {
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.floor(window.innerWidth * dpr);
    canvas.height = Math.floor(window.innerHeight * dpr);
    canvas.style.width = window.innerWidth + 'px';
    canvas.style.height = window.innerHeight + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0); // scale back for drawing in CSS pixels
    W = window.innerWidth;
    H = window.innerHeight;
    recomputeLayout();
  }
  window.addEventListener('resize', resizeCanvas);

  // Game state
  let W = window.innerWidth, H = window.innerHeight;
  let groundY, archerX, archerY, arrowLen, gravity;
  const BASE_GRAV = 900; // px/s^2
  const DRAG = 0.03;
  const MIN_SPEED = 260;
  const MAX_SPEED = 1050;
  const MAX_CHARGE = 1.6;
  arrowLen = Math.max(40, Math.min(W,H)*0.07);

  function recomputeLayout(){
    groundY = Math.floor(H * 0.82);
    archerX = Math.floor(W * 0.12);
    archerY = groundY - Math.floor(H * 0.05);
    arrowLen = Math.floor(Math.min(W,H) * 0.07);
    gravity = BASE_GRAV * Math.min(W/960, H/640);
  }

  // Entities
  class Arrow {
    constructor(x,y,vx,vy,angle){
      this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.angle=angle;
      this.stuck=false; this.stuckTo=null; this.localX=0; this.localY=0;
    }
    tip(){
      return { x: this.x + Math.cos(this.angle)*arrowLen/2, y: this.y + Math.sin(this.angle)*arrowLen/2 };
    }
    update(dt){
      if (this.stuck) {
        if (this.stuckTo) {
          const tipx = this.stuckTo.x + this.localX, tipy = this.stuckTo.y + this.localY;
          this.x = tipx - Math.cos(this.angle)*arrowLen/2;
          this.y = tipy - Math.sin(this.angle)*arrowLen/2;
        }
        return;
      }
      this.vy += gravity * dt;
      this.vx -= this.vx * DRAG * dt;
      this.vy -= this.vy * DRAG * dt;
      this.x += this.vx * dt;
      this.y += this.vy * dt;
      this.angle = Math.atan2(this.vy, this.vx);
    }
    draw(ctx){
      ctx.save();
      ctx.translate(this.x, this.y);
      ctx.rotate(this.angle);
      ctx.lineWidth = Math.max(2, W * 0.003);
      // shaft
      ctx.strokeStyle = '#8c6b3f';
      ctx.beginPath();
      ctx.moveTo(-arrowLen/2,0); ctx.lineTo(arrowLen/2 - 8,0); ctx.stroke();
      // tip
      ctx.fillStyle = '#444';
      ctx.beginPath();
      const tip = arrowLen/2;
      ctx.moveTo(tip,0); ctx.lineTo(tip-12,-8); ctx.lineTo(tip-12,8); ctx.closePath(); ctx.fill();
      // fletch
      ctx.fillStyle = '#eee';
      ctx.fillRect(-arrowLen/2 - 8, -6, 8, 4);
      ctx.fillRect(-arrowLen/2 - 8, 2, 8, 4);
      ctx.restore();
    }
    stickTo(target, tipx, tipy){
      this.stuck = true; this.stuckTo = target;
      this.localX = tipx - target.x; this.localY = tipy - target.y;
      // set body pos so tip matches visually
      this.x = tipx - Math.cos(this.angle)*arrowLen/2;
      this.y = tipy - Math.sin(this.angle)*arrowLen/2;
      this.vx = this.vy = 0;
    }
  }

  class Target {
    constructor(x,y,r,vx){
      this.x=x; this.y=y; this.radius=r; this.vx=vx;
      this.rings = []; this.points = [10,30,60,100];
      this.setup();
      this.wobble = 0;
    }
    setup(){
      this.rings = [this.radius, Math.floor(this.radius*0.72), Math.floor(this.radius*0.48), Math.floor(this.radius*0.28)];
    }
    update(dt){
      this.x += this.vx * dt;
      this.wobble *= 0.94;
      if (this.x < -this.radius - 40){
        this.x = W + this.radius + 40;
        this.y = 120 + Math.random() * Math.max(1, groundY - 200);
      }
    }
    hit(px,py){
      return Math.hypot(px - this.x, py - this.y) <= this.radius;
    }
    pointsFor(px,py){
      const d = Math.hypot(px - this.x, py - this.y);
      for (let i = this.rings.length - 1; i >= 0; --i) if (d <= this.rings[i]) return this.points[i];
      return 0;
    }
    wobbleNow(){ this.wobble = 8 + Math.random()*18; }
    draw(ctx){
      const colors = ['#fff','#000','#2850c8','#c33']; // outer to inner
      for (let i=0;i<this.rings.length;i++){
        ctx.fillStyle = colors[i%colors.length];
        const r = this.rings[i];
        ctx.beginPath();
        ctx.ellipse(this.x, this.y + Math.sin(this.wobble*0.07), r*2, r*2, 0, 0, Math.PI*2);
        ctx.fill();
      }
      // center
      const cent = Math.max(6, Math.floor(this.radius*0.12));
      ctx.fillStyle = '#ffdb4d';
      ctx.beginPath(); ctx.arc(this.x, this.y + Math.sin(this.wobble*0.07), cent, 0, Math.PI*2); ctx.fill();
      ctx.strokeStyle = '#333'; ctx.lineWidth = Math.max(1, W*0.002);
      ctx.beginPath(); ctx.arc(this.x, this.y + Math.sin(this.wobble*0.07), this.radius, 0, Math.PI*2); ctx.stroke();
    }
  }

  class Particle {
    constructor(x,y,vx,vy,color,life){
      this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.color=color;this.life=life;this.init=life;
    }
    update(dt){
      this.vy += gravity * dt;
      this.vx -= this.vx * 1.2 * dt;
      this.vy -= this.vy * 0.6 * dt;
      this.x += this.vx * dt; this.y += this.vy * dt;
      this.life -= dt;
    }
    draw(ctx){
      const a = Math.max(0, this.life / this.init);
      ctx.fillStyle = colorAlpha(this.color, a);
      const s = Math.floor(3 + 6 * (1 - a));
      ctx.beginPath(); ctx.arc(this.x, this.y, s, 0, Math.PI*2); ctx.fill();
    }
  }

  // helpers
  function colorAlpha(hex, alpha){
    // hex like '#rrggbb'
    const r = parseInt(hex.substr(1,2),16), g = parseInt(hex.substr(3,2),16), b = parseInt(hex.substr(5,2),16);
    return `rgba(${r},${g},${b},${alpha})`;
  }

  // game variables
  let arrows = [], particles = [], powerups = [], popups = [];
  let target;
  let score = 0, arrowsLeft = 12, timeLeft = 60, highScore = 0;
  let charging=false, chargeStart=0, aimAngle = -Math.PI/8, pointerActive=false, pointerX=0, pointerY=0;
  let lastTime = performance.now();
  let running = false, paused=false;

  function init(){
    resizeCanvas();
    loadHighScore();
    const r = Math.floor(Math.min(W,H) * 0.09);
    target = new Target(W - Math.floor(W*0.18), groundY - Math.floor(H*0.18), r, -160);
    applyDifficulty(diffSel.value);
    attachHandlers();
    drawFrame(); // initial
  }

  // input (mouse & touch)
  function attachHandlers(){
    // mouse
    canvas.addEventListener('pointerdown', e=>{
      if (!running) return;
      if (isOverUI(e.clientX, e.clientY)) return;
      pointerActive = true; pointerX = e.clientX; pointerY = e.clientY;
      aimAngle = clamp(Math.atan2(pointerY - archerY, pointerX - archerX));
      startCharge();
    });
    canvas.addEventListener('pointerup', e=>{
      if (!running) return;
      if (!pointerActive) return;
      pointerActive = false;
      releaseFire(e.clientX, e.clientY);
    });
    canvas.addEventListener('pointermove', e=>{
      pointerX = e.clientX; pointerY = e.clientY;
      aimAngle = clamp(Math.atan2(pointerY - archerY, pointerX - archerX));
    });

    // UI buttons
    startBtn.addEventListener('click', ()=>{ startGame(); });
    pauseBtn.addEventListener('click', ()=>{ togglePause(); });
    restartBtn.addEventListener('click', ()=>{ restartGame(); });
    diffSel.addEventListener('change', ()=>{ applyDifficulty(diffSel.value); });

    // keyboard
    window.addEventListener('keydown', e=>{
      if (e.key === 'p' || e.key === 'P') togglePause();
      if (e.key === 'r' || e.key === 'R') restartGame();
    });
  }

  function isOverUI(x,y){
    return x > window.innerWidth - 260;
  }

  function startCharge(){
    if (arrowsLeft <= 0) return;
    charging = true; chargeStart = performance.now();
  }

  function releaseFire(mx, my){
    if (!charging) return;
    charging = false;
    const held = Math.min((performance.now() - chargeStart)/1000, MAX_CHARGE);
    const t = held / MAX_CHARGE;
    const eased = Math.sin(t * Math.PI * 0.5);
    const speed = MIN_SPEED + eased * (MAX_SPEED - MIN_SPEED);
    const angle = clamp(Math.atan2(my - archerY, mx - archerX));
    const vx = Math.cos(angle) * speed;
    const vy = Math.sin(angle) * speed;
    const startx = archerX + Math.cos(angle)*(arrowLen/2 + 10);
    const starty = archerY + Math.sin(angle)*(arrowLen/2 + 10);
    arrows.push(new Arrow(startx, starty, vx, vy, angle));
    arrowsLeft = Math.max(0, arrowsLeft-1);
    updateHUD();
  }

  function clamp(a){
    const min = -Math.PI/3, max = Math.PI/3;
    return Math.max(min, Math.min(max, a));
  }

  // control functions
  function startGame(){
    score = 0; timeLeft = (diffSel.value === 'Easy' ? 80 : diffSel.value==='Hard'?45:60);
    arrowsLeft = (diffSel.value==='Easy'?20: diffSel.value==='Hard'?10:14);
    arrows = []; particles = []; powerups = []; popups = [];
    applyDifficulty(diffSel.value);
    running = true; paused = false; lastTime = performance.now();
    updateHUD();
  }
  function restartGame(){ startGame(); }
  function togglePause(){ if (!running) return; paused = !paused; }

  function applyDifficulty(which){
    if (!target) return;
    if (which === 'Easy'){ arrowsLeft = 20; timeLeft = 80; target.radius = Math.floor(Math.min(W,H)*0.12); target.vx = -120; }
    else if (which === 'Hard'){ arrowsLeft = 10; timeLeft = 45; target.radius = Math.floor(Math.min(W,H)*0.07); target.vx = -260; }
    else { arrowsLeft = 14; timeLeft = 60; target.radius = Math.floor(Math.min(W,H)*0.09); target.vx = -180; }
    target.setup();
    updateHUD();
  }

  // spawn helpers
  function spawnParticles(x,y,baseColor,count){
    for (let i=0;i<count;i++){
      const ang = Math.random()*Math.PI*2;
      const sp = 60 + Math.random()*220;
      const vx = Math.cos(ang)*sp, vy = Math.sin(ang)*sp*0.6;
      particles.push(new Particle(x,y,vx,vy,baseColor,0.6 + Math.random()*0.8));
    }
  }

  // popup text
  class Popup { constructor(x,y,text,life){ this.x=x;this.y=y;this.text=text;this.life=life; } update(dt){ this.life -= dt; } draw(ctx){ ctx.fillStyle = `rgba(255,255,255,${Math.max(0, Math.min(1, this.life / 1.0))})`; ctx.font = `${Math.max(12, W/48)}px sans-serif`; ctx.fillText(this.text, this.x + 8, this.y - 8); } }

  // main loop
  function drawFrame(ts){
    const now = performance.now();
    let dt = (now - lastTime)/1000;
    if (dt > 0.05) dt = 0.05;
    lastTime = now;

    if (running && !paused){
      // update game time each real second
      timeLeft -= dt;
      if (timeLeft <= 0){
        running = false;
        if (score > highScore){ highScore = score; saveHighScore(); }
        alert(`Time's up!\nScore: ${score}\nHigh Score: ${highScore}`);
      }

      // target update
      target.update(dt);

      // random powerups
      if (Math.random() < 0.003) {
        const px = W + 40; const py = 120 + Math.random()*(Math.max(1, groundY - 200));
        powerups.push({ x: px, y: py, vx: -100, bob: Math.random()*2 });
      }

      // update powerups
      for (let i=powerups.length-1;i>=0;i--){
        const p = powerups[i]; p.x += p.vx*dt; p.bob += dt;
        if (p.x < -100) powerups.splice(i,1);
      }

      // update arrows
      for (let i=arrows.length-1;i>=0;i--){
        const a = arrows[i];
        if (!a.stuck){
          a.update(dt);
          const tip = a.tip();
          // powerup collisions
          for (let j=powerups.length-1;j>=0;j--){
            const pu = powerups[j];
            if (Math.hypot(tip.x - pu.x, tip.y - pu.y) < Math.max(18, W*0.03)){
              arrowsLeft += 2; spawnParticles(pu.x, pu.y, '#0ff', 12); popups.push(new Popup(pu.x, pu.y, '+2 Arrows', 0.9));
              powerups.splice(j,1);
            }
          }
          // target collision
          if (target.hit(tip.x, tip.y)){
            const pts = target.pointsFor(tip.x, tip.y);
            score += pts;
            spawnParticles(tip.x, tip.y, (pts>=100?'#ffd54d':'#ff9e80'), 18);
            popups.push(new Popup(tip.x, tip.y, commentaryFor(pts), 1.1));
            target.wobbleNow();
            a.stickTo(target, tip.x, tip.y);
            if (pts >= 100) popups.push(new Popup(tip.x, tip.y - 30, 'BULLSEYE!', 1.4));
            updateHUD();
          } else {
            // remove if offscreen long distance
            if (a.x < -400 || a.x > W + 400 || a.y > H + 400 || a.y < -400) arrows.splice(i,1);
          }
        } else {
          a.update(dt); // keep it synced to target
        }
      }

      // update particles
      for (let i=particles.length-1;i>=0;i--){
        particles[i].update(dt);
        if (particles[i].life <= 0) particles.splice(i,1);
      }
      // update popups
      for (let i=popups.length-1;i>=0;i--){
        popups[i].update(dt);
        if (popups[i].life <= 0) popups.splice(i,1);
      }
    }

    // draw everything
    render();

    // schedule next frame
    requestAnimationFrame(drawFrame);
  }

  function commentaryFor(pts){
    if (pts >= 100) return 'Excellent!';
    if (pts >= 60) return 'Very Good!';
    if (pts >= 30) return 'Good!';
    return 'Nice!';
  }

  // render
  function render(){
    // clear
    ctx.fillStyle = '#87ceeb';
    ctx.fillRect(0,0,W,H);

    // sky clouds (simple)
    ctx.fillStyle = 'rgba(255,255,255,0.9)';
    ctx.beginPath(); ctx.ellipse(W*0.12, H*0.08, W*0.12, H*0.06, 0,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(W*0.74, H*0.06, W*0.14, H*0.06, 0,0,Math.PI*2); ctx.fill();

    // ground
    ctx.fillStyle = '#2d8a2d';
    ctx.fillRect(0, groundY, W, H-groundY);
    ctx.fillStyle = '#256e25';
    ctx.beginPath(); ctx.ellipse(W*0.15, groundY - H*0.15, W*0.5, H*0.18, 0,0,Math.PI*2); ctx.fill();
    ctx.beginPath(); ctx.ellipse(W*0.6, groundY - H*0.18, W*0.5, H*0.2, 0,0,Math.PI*2); ctx.fill();

    // powerups
    powerups.forEach(p=>{
      const s = Math.max(16, W*0.03);
      ctx.fillStyle = '#28a745';
      ctx.beginPath(); ctx.arc(p.x, p.y + Math.sin(p.bob*3)*3, s/2, 0, Math.PI*2); ctx.fill();
      ctx.fillStyle = '#000'; ctx.font = `${Math.max(10,W/60)}px sans-serif`; ctx.fillText('+A', p.x - s*0.2, p.y + 6 + Math.sin(p.bob*3)*3);
    });

    // target (behind stuck arrows)
    target.draw(ctx);

    // stuck arrows first
    arrows.filter(a=>a.stuck).forEach(a=>a.draw(ctx));

    // archer (draw over stuck arrows base)
    drawStickman();

    // flying arrows
    arrows.filter(a=>!a.stuck).forEach(a=>a.draw(ctx));

    // particles and popups
    particles.forEach(p=>p.draw(ctx));
    popups.forEach(pp=>pp.draw(ctx));
  }

  function drawStickman(){
    const headR = Math.floor(Math.min(W,H)*0.03);
    const bodyLen = Math.floor(Math.min(W,H)*0.12);
    const cx = archerX, cy = archerY - Math.floor(bodyLen/2);

    ctx.lineWidth = Math.max(2, W*0.003);
    ctx.strokeStyle = '#3d2a14';
    ctx.beginPath();
    // legs
    ctx.moveTo(cx, cy + bodyLen/2); ctx.lineTo(cx - W*0.03, cy + bodyLen);
    ctx.moveTo(cx, cy + bodyLen/2); ctx.lineTo(cx + W*0.03, cy + bodyLen);
    // torso
    ctx.moveTo(cx, cy - headR/2); ctx.lineTo(cx, cy + bodyLen/2);
    ctx.stroke();

    // head
    ctx.fillStyle = '#e6bfa0';
    ctx.beginPath(); ctx.arc(cx, cy - bodyLen/2 - headR, headR, 0, Math.PI*2); ctx.fill();

    // shoulders
    const shoulderX = cx, shoulderY = cy - bodyLen/4;

    // bow transform and draw
    ctx.save();
    ctx.translate(shoulderX, shoulderY);
    ctx.rotate(aimAngle);
    const bowLen = Math.floor(Math.min(W,H)*0.14);
    ctx.lineWidth = Math.max(3, W*0.006);
    ctx.strokeStyle = '#7a4b26';
    ctx.beginPath();
    // simple arc for bow
    ctx.arc(0, 0, bowLen/1.0, Math.PI*0.5, -Math.PI*0.5, true);
    ctx.stroke();

    // string
    ctx.strokeStyle = '#333';
    ctx.lineWidth = 2;
    const pull = charging ? (6 + 36 * Math.sin(Math.min((performance.now()-chargeStart)/1000 / MAX_CHARGE,1) * Math.PI*0.5)) : 0;
    ctx.beginPath(); ctx.moveTo(0, -bowLen/2); ctx.lineTo(-pull, 0); ctx.lineTo(0, bowLen/2); ctx.stroke();

    // arrow on string while charging
    if (charging){
      ctx.lineWidth = 4; ctx.strokeStyle = '#be8b4c';
      ctx.beginPath(); ctx.moveTo(-pull,0); ctx.lineTo(-pull - arrowLen, 0); ctx.stroke();
    } else {
      ctx.lineWidth = 4; ctx.strokeStyle = '#be8b4c';
      ctx.beginPath(); ctx.moveTo(10,0); ctx.lineTo(10 + arrowLen, 0); ctx.stroke();
    }

    ctx.restore();

    // arms
    ctx.lineWidth = Math.max(2, W*0.004); ctx.strokeStyle = '#3d2a14';
    const handX = shoulderX + Math.cos(aimAngle) * (charging ? - (pullEstimate()) : -40);
    const handY = shoulderY + Math.sin(aimAngle) * (charging ? - (pullEstimate()) : -40);
    ctx.beginPath(); ctx.moveTo(shoulderX, shoulderY); ctx.lineTo(handX, handY); ctx.stroke();
    const bowHandX = shoulderX + Math.cos(aimAngle + Math.PI/2) * 10;
    const bowHandY = shoulderY + Math.sin(aimAngle + Math.PI/2) * 10;
    ctx.beginPath(); ctx.moveTo(shoulderX, shoulderY); ctx.lineTo(bowHandX, bowHandY); ctx.stroke();
  }

  function pullEstimate(){ if (!charging) return 40; const held = Math.min((performance.now()-chargeStart)/1000, MAX_CHARGE); const t = held / MAX_CHARGE; const pull = 6 + 36 * Math.sin(t * Math.PI * 0.5); return pull + 40; }

  // HUD
  function updateHUD(){ scoreEl.textContent = `Score: ${score}`; highEl.textContent = `High: ${highScore}`; arrowsEl.textContent = `Arrows: ${arrowsLeft}`; timeEl.textContent = `Time: ${Math.max(0,Math.ceil(timeLeft))}s`; }

  // highscore persistence
  function loadHighScore(){ try { const v = localStorage.getItem('archery_high'); if (v) highScore = parseInt(v); } catch(e){ highScore = 0; } updateHUD(); }
  function saveHighScore(){ try { localStorage.setItem('archery_high', String(highScore)); } catch(e){} }

  // commentary helper
  function commentaryFor(pts){
    if (pts >= 100) return 'Excellent!';
    if (pts >= 60) return 'Very Good!';
    if (pts >= 30) return 'Good!';
    return 'Nice!';
  }

  // init and start rendering
  resizeCanvas();
  init();
  requestAnimationFrame(drawFrame);

  // Expose a few things to global for testing (optional)
  window.__archery = { startGame, restartGame, togglePause, arrows, particles };

})();
