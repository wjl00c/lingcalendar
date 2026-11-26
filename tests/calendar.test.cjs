const assert=require('node:assert/strict'),R=require('../shared/calendar.js');
assert.equal(R.grid('2026-11-01')[0],'2026-10-26');assert.equal(R.grid('2026-11-01')[41],'2026-12-06');assert.equal(R.grid('2026-11-01').length,42);
assert.equal(R.valid('2026-02-30'),false);assert.equal(R.valid('2024-02-29'),true);
for(const [day,label]of [['2026-02-17','春节'],['2026-02-16','除夕'],['2026-06-19','端午节'],['2026-09-25','中秋节'],['2026-04-05','清明节'],['2026-12-22','冬至']])assert.equal(R.lunar(day).label,label,day);
const i={Id:'a',Title:'测试',Kind:'task',Start:'2026-01-31',End:'2026-01-31',Time:'',Category:'学习',Notes:'',Repeat:'monthly',Until:'',Reminder:-1,DoneDates:['2026-03-31']};
R.validate(i);assert.equal(R.occurrence(i,'2026-02-28'),null);assert.equal(R.occurrence(i,'2026-03-31'),'2026-03-31');assert.equal(R.done(i,'2026-03-31'),true);assert.equal(R.done(i,'2026-04-30'),false);assert.throws(()=>R.validate({...i,End:'2026-02-01'}));assert.throws(()=>R.validate({...i,Time:'25:00'}));
console.log('PASS cross-month grid, leap dates, lunar festivals, recurring dates, completion state, input validation');
